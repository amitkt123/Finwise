package org.amit.finwise.cfo.service.macro;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSnapshot;
import org.amit.finwise.cfo.repository.MacroSnapshotRepository;
import org.amit.finwise.cfo.service.price.PriceDataProvider;
import org.amit.finwise.cfo.service.price.YahooFinancePriceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Fetches and maintains a daily snapshot of Indian macro state.
 *
 * Data sources:
 *   - USD/INR: Yahoo Finance (USDINR=X)
 *   - India VIX: stubbed (NSE endpoint requires scraping/auth)
 *   - RBI repo rate: manual config (@Value, updated when RBI announces);
 *     optional live cross-check via RbiRateProvider (off by default)
 *   - CPI, GDP, G-sec yield curve: manual config (refreshed periodically)
 *   - FII/DII flows: NSE fiidiiTradeReact via FiiDiiFlowProvider (19:00 update)
 *
 * All fields are optional; data gaps are captured in dataQualityNotes.
 * The service is invoked by the scheduler (~5PM) to create a daily snapshot;
 * updateFlows() runs at 19:00 once NSE publishes institutional flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroStateService {

    /** 5-day cumulative FII net outflow beyond this (₹ Cr) flags FLOW_PRESSURE. */
    private static final double FLOW_PRESSURE_THRESHOLD_CR = 10_000;

    private final MacroSnapshotRepository macroRepo;
    private final YahooFinancePriceProvider yahooProvider;
    private final YieldCurveService yieldCurveService;
    private final RbiRateProvider rbiRateProvider;
    private final FiiDiiFlowProvider fiiDiiFlowProvider;

    @Value("${cfo.macro.repo-rate:#{null}}")
    private BigDecimal configRepoRate;

    @Value("${cfo.macro.repo-rate-as-of:#{null}}")
    private String configRepoRateAsOf;

    @Value("${cfo.macro.cpi-yoy:#{null}}")
    private BigDecimal configCpiYoY;

    @Value("${cfo.macro.cpi-as-of:#{null}}")
    private String configCpiAsOf;

    @Value("${cfo.macro.gsec-yield-10y:#{null}}")
    private BigDecimal configGsecYield10Y;

    @Value("${cfo.macro.gsec-yield-as-of:#{null}}")
    private String configGsecYieldAsOf;

    @Value("${cfo.macro.gdp-growth:#{null}}")
    private BigDecimal configGdpGrowth;

    @Value("${cfo.macro.gdp-as-of:#{null}}")
    private String configGdpAsOf;

    /**
     * Fetch and persist today's macro snapshot.
     * Idempotent: if a snapshot already exists for today, it is replaced.
     */
    public MacroSnapshot fetchAndPersistDaily() {
        LocalDate today = LocalDate.now();

        List<String> dataGaps = new ArrayList<>();

        // Fetch USD/INR from Yahoo
        BigDecimal usdInr = null;
        LocalDateTime usdInrFetchedAt = null;
        try {
            usdInr = fetchUSDINR();
            usdInrFetchedAt = LocalDateTime.now();
        } catch (Exception e) {
            log.warn("[Macro] Failed to fetch USD/INR: {}", e.getMessage());
            dataGaps.add("FETCH_ERROR:USD_INR");
        }

        // Fetch India VIX
        BigDecimal indiaVix = null;
        LocalDateTime indiaVixFetchedAt = null;
        try {
            indiaVix = fetchIndiaVIX();
            if (indiaVix != null) indiaVixFetchedAt = LocalDateTime.now();
        } catch (Exception e) {
            log.warn("[Macro] Failed to fetch India VIX: {}", e.getMessage());
            dataGaps.add("FETCH_ERROR:INDIA_VIX");
        }

        // Build snapshot from config + live fetches
        LocalDate repoRateAsOf = configRepoRateAsOf != null ? LocalDate.parse(configRepoRateAsOf) : null;
        LocalDate gsecYieldAsOf = configGsecYieldAsOf != null ? LocalDate.parse(configGsecYieldAsOf) : null;
        LocalDate gdpAsOf = configGdpAsOf != null ? LocalDate.parse(configGdpAsOf) : null;
        addFreshnessNotes(dataGaps, today, configRepoRate, repoRateAsOf, configCpiYoY, configCpiAsOf,
                configGsecYield10Y, gsecYieldAsOf, configGdpGrowth, gdpAsOf, usdInr, indiaVix);

        // Optional live RBI cross-check — config stays the source of truth;
        // a disagreement only nags the user to update the config.
        if (rbiRateProvider.isEnabled() && configRepoRate != null) {
            rbiRateProvider.fetchLiveRepoRate().ifPresent(live -> {
                if (live.subtract(configRepoRate).abs().doubleValue() > 0.011) {
                    dataGaps.add(String.format("RBI_LIVE_RATE_DIFFERS: scraped %.2f%% vs config %.2f%% — update cfo.macro.repo-rate",
                            live.doubleValue(), configRepoRate.doubleValue()));
                }
            });
        }

        MacroSnapshot snapshot = MacroSnapshot.builder()
                .snapshotDate(today)
                .repoRate(configRepoRate)
                .repoRateAsOf(repoRateAsOf)
                .cpiYoY(configCpiYoY)
                .cpiAsOf(configCpiAsOf)
                .gsec3m(yieldCurveService.gsec3m())
                .gsec1y(yieldCurveService.gsec1y())
                .gsec5y(yieldCurveService.gsec5y())
                .gsecYield10Y(configGsecYield10Y)
                .gsecYieldAsOf(gsecYieldAsOf)
                .curveSlope10y3m(yieldCurveService.slope10y3m())
                .gdpGrowth(configGdpGrowth)
                .gdpAsOf(gdpAsOf)
                .usdInr(usdInr)
                .usdInrFetchedAt(usdInrFetchedAt)
                .indiaVix(indiaVix)
                .indiaVixFetchedAt(indiaVixFetchedAt)
                .dataQualityNotes(dataGaps.isEmpty() ? null : String.join("; ", dataGaps))
                .build();

        // Delete previous snapshot for today (idempotent), carrying over any
        // flow figures the 19:00 job may already have written.
        Optional<MacroSnapshot> existingOpt = macroRepo.findBySnapshotDate(today);
        if (existingOpt.isPresent()) {
            MacroSnapshot existing = existingOpt.get();
            snapshot.setFiiNetFlow(existing.getFiiNetFlow());
            snapshot.setDiiNetFlow(existing.getDiiNetFlow());
            snapshot.setFiiNetFlow5d(existing.getFiiNetFlow5d());
            snapshot.setFlowsAsOf(existing.getFlowsAsOf());
            macroRepo.delete(existing);
        }

        return macroRepo.save(snapshot);
    }

    /**
     * 19:00 IST update: fetch FII/DII flows from NSE (published post-market)
     * into today's snapshot, and recompute the 5-day cumulative FII figure.
     * Failure leaves the flow fields null with a staleness note — consumers
     * are null-tolerant.
     */
    public void updateFlows() {
        MacroSnapshot snapshot = macroRepo.findBySnapshotDate(LocalDate.now())
                .orElseGet(this::fetchAndPersistDaily);

        FiiDiiFlowProvider.FiiDiiFlow flow = fiiDiiFlowProvider.fetchLatestFlow();
        if (flow == null) {
            appendNote(snapshot, "STALE:FII_DII_FLOWS — NSE fetch failed, flows unavailable");
            macroRepo.save(snapshot);
            return;
        }

        snapshot.setFiiNetFlow(flow.fiiNetFlowCr());
        snapshot.setDiiNetFlow(flow.diiNetFlowCr());
        snapshot.setFlowsAsOf(flow.asOf());
        snapshot.setFiiNetFlow5d(fiveDayFiiCumulative(flow.fiiNetFlowCr()));

        if (snapshot.getFiiNetFlow5d() != null
                && Math.abs(snapshot.getFiiNetFlow5d().doubleValue()) > FLOW_PRESSURE_THRESHOLD_CR) {
            log.info("[Macro] FLOW_PRESSURE: 5-day cumulative FII net flow ₹{} Cr",
                    snapshot.getFiiNetFlow5d());
        }
        macroRepo.save(snapshot);
    }

    /**
     * Today's FII net flow plus the four most recent prior snapshots that have
     * flow data. With fewer than 5 days of history this is a partial sum —
     * acceptable: it under-triggers, never over-triggers, the ₹10,000 Cr signal.
     */
    private BigDecimal fiveDayFiiCumulative(BigDecimal todayFii) {
        BigDecimal sum = todayFii;
        List<MacroSnapshot> priors = macroRepo
                .findTop10BySnapshotDateBeforeOrderBySnapshotDateDesc(LocalDate.now());
        int counted = 0;
        for (MacroSnapshot prior : priors) {
            if (prior.getFiiNetFlow() == null) continue;
            sum = sum.add(prior.getFiiNetFlow());
            if (++counted == 4) break;
        }
        return sum;
    }

    private void appendNote(MacroSnapshot snapshot, String note) {
        String existing = snapshot.getDataQualityNotes();
        if (existing != null && existing.contains(note)) return;
        snapshot.setDataQualityNotes(existing == null || existing.isBlank() ? note : existing + "; " + note);
    }

    private void addFreshnessNotes(List<String> dataGaps,
                                   LocalDate today,
                                   BigDecimal repoRate,
                                   LocalDate repoRateAsOf,
                                   BigDecimal cpiYoY,
                                   String cpiAsOf,
                                   BigDecimal gsecYield10Y,
                                   LocalDate gsecYieldAsOf,
                                   BigDecimal gdpGrowth,
                                   LocalDate gdpAsOf,
                                   BigDecimal usdInr,
                                   BigDecimal indiaVix) {
        if (repoRate == null) dataGaps.add("MISSING:REPO_RATE");
        else if (repoRateAsOf == null) dataGaps.add("MISSING:REPO_RATE_AS_OF");
        else if (ChronoUnit.DAYS.between(repoRateAsOf, today) > 120) dataGaps.add("STALE:REPO_RATE");

        if (cpiYoY == null) dataGaps.add("MISSING:CPI_YOY");
        else {
            LocalDate cpiDate = parseMonthEnd(cpiAsOf);
            if (cpiDate == null) dataGaps.add("MISSING:CPI_AS_OF");
            else if (ChronoUnit.DAYS.between(cpiDate, today) > 75) dataGaps.add("STALE:CPI_YOY");
        }

        if (gsecYield10Y == null) dataGaps.add("MISSING:GSEC_10Y");
        else if (gsecYieldAsOf == null) dataGaps.add("MISSING:GSEC_10Y_AS_OF");
        else if (ChronoUnit.DAYS.between(gsecYieldAsOf, today) > 14) dataGaps.add("STALE:GSEC_10Y");

        if (gdpGrowth == null) dataGaps.add("MISSING:GDP_GROWTH");
        else if (gdpAsOf == null) dataGaps.add("MISSING:GDP_AS_OF");
        else if (ChronoUnit.DAYS.between(gdpAsOf, today) > 135) dataGaps.add("STALE:GDP_GROWTH");

        if (usdInr == null) dataGaps.add("MISSING:USD_INR");
        if (indiaVix == null) dataGaps.add("MISSING:INDIA_VIX");
    }

    private LocalDate parseMonthEnd(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return YearMonth.parse(value, DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)).atEndOfMonth();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the latest macro snapshot. If stale (not from today), refresh it.
     */
    public Optional<MacroSnapshot> getLatest() {
        Optional<MacroSnapshot> latestOpt = macroRepo.findTopByOrderBySnapshotDateDesc();

        if (latestOpt.isPresent()) {
            MacroSnapshot latest = latestOpt.get();
            if (latest.getSnapshotDate().equals(LocalDate.now())) {
                return latestOpt; // Fresh
            }
        }

        // Stale or absent; refresh
        MacroSnapshot refreshed = fetchAndPersistDaily();
        return Optional.of(refreshed);
    }

    /**
     * Fetch USD/INR exchange rate from Yahoo Finance (USDINR=X).
     * Returns the rate as BigDecimal or null on error.
     */
    private BigDecimal fetchUSDINR() throws Exception {
        try {
            // Yahoo's chart endpoint for USDINR=X
            List<PriceDataProvider.DailyPrice> prices =
                    yahooProvider.fetchHistory("USDINR", 1);
            if (prices != null && !prices.isEmpty()) {
                PriceDataProvider.DailyPrice latest =
                        prices.get(prices.size() - 1);
                return latest.close();
            }
            log.warn("[Macro] No price data returned for USDINR");
            return null;
        } catch (PriceDataProvider.PriceProviderException e) {
            log.warn("[Macro] Yahoo provider error for USDINR: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Fetch India VIX from Yahoo (^NSEXIT).
     * Returns the VIX level or null on error.
     *
     * Note: India VIX is published by NSE but available via Yahoo Finance.
     * Direct NSE scraping would require authentication/complex parsing.
     */
    private BigDecimal fetchIndiaVIX() throws Exception {
        try {
            List<PriceDataProvider.DailyPrice> prices =
                    yahooProvider.fetchHistory("^NSEXIT", 1);
            if (prices != null && !prices.isEmpty()) {
                PriceDataProvider.DailyPrice latest =
                        prices.get(prices.size() - 1);
                return latest.close();
            }
            log.warn("[Macro] No price data returned for India VIX");
            return null;
        } catch (PriceDataProvider.PriceProviderException e) {
            log.warn("[Macro] Yahoo provider error for India VIX: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Compute Sharpe ratio numerator: annualized portfolio return - risk-free rate.
     * Uses the latest macro snapshot's 10Y G-sec yield as R_f.
     *
     * Returns (annualizedReturn - R_f) or null if R_f is missing.
     */
    public BigDecimal computeSharpeNumerator(double annualizedReturn) {
        Optional<MacroSnapshot> latestOpt = getLatest();
        if (latestOpt.isEmpty() || latestOpt.get().getGsecYield10Y() == null) {
            return null;
        }

        BigDecimal riskFree = latestOpt.get().getGsecYield10Y().divide(
                new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
        return BigDecimal.valueOf(annualizedReturn).subtract(riskFree);
    }
}
