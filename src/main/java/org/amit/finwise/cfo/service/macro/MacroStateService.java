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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches and maintains a daily snapshot of Indian macro state.
 *
 * Data sources:
 *   - USD/INR: Yahoo Finance (USDINR=X)
 *   - India VIX: stubbed (NSE endpoint requires scraping/auth)
 *   - RBI repo rate: manual config (@Value, updated when RBI announces)
 *   - CPI, GDP, 10Y G-sec yield: manual config (refreshed periodically)
 *   - FII/DII flows: manual config (from NSDL/NSE announcements)
 *
 * All fields are optional; data gaps are captured in dataQualityNotes.
 * The service is invoked by the scheduler (~5PM) to create a daily snapshot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroStateService {

    private final MacroSnapshotRepository macroRepo;
    private final YahooFinancePriceProvider yahooProvider;

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
        MacroSnapshot snapshot = MacroSnapshot.builder()
                .snapshotDate(today)
                .repoRate(configRepoRate)
                .repoRateAsOf(configRepoRateAsOf != null ? LocalDate.parse(configRepoRateAsOf) : null)
                .cpiYoY(configCpiYoY)
                .cpiAsOf(configCpiAsOf)
                .gsecYield10Y(configGsecYield10Y)
                .gsecYieldAsOf(configGsecYieldAsOf != null ? LocalDate.parse(configGsecYieldAsOf) : null)
                .gdpGrowth(configGdpGrowth)
                .gdpAsOf(configGdpAsOf != null ? LocalDate.parse(configGdpAsOf) : null)
                .usdInr(usdInr)
                .usdInrFetchedAt(usdInrFetchedAt)
                .indiaVix(indiaVix)
                .indiaVixFetchedAt(indiaVixFetchedAt)
                .dataQualityNotes(dataGaps.isEmpty() ? null : String.join("; ", dataGaps))
                .build();

        // Delete previous snapshot for today (idempotent)
        Optional<MacroSnapshot> existingOpt = macroRepo.findBySnapshotDate(today);
        existingOpt.ifPresent(macroRepo::delete);

        return macroRepo.save(snapshot);
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
