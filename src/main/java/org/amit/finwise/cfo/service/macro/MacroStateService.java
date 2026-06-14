package org.amit.finwise.cfo.service.macro;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeries;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.amit.finwise.cfo.model.MacroSnapshot;
import org.amit.finwise.cfo.repository.MacroSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Maintains a daily snapshot of Indian macro state.
 *
 * <p>As of DF-3 the snapshot is assembled from the generic {@code macro_series}
 * table — FBIL G-sec yields + USD/INR, RBI policy rates, MOSPI CPI, and India
 * VIX from DF-1's index feed — with the legacy {@code cfo.macro.*} config
 * constants kept only as a transitional fallback for series not yet ingested.
 * Yahoo is no longer called for USD/INR or VIX.
 *
 * <p>Staleness is now a per-series SLA check ({@link MacroSeriesCode#slaMaxAgeDays()}):
 *   <ul>
 *     <li>{@code STALE:<CODE>} — series ingested but the latest observation is
 *         older than its SLA;</li>
 *     <li>{@code CONFIG_FALLBACK:<CODE>} — no series rows yet, value taken from
 *         config (seed the series to clear it);</li>
 *     <li>{@code MISSING:<CODE>} — neither series nor config supplies a value.</li>
 *   </ul>
 *
 * <p>FII/DII flows are still pulled from NSE by {@link #updateFlows()} at 19:00.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroStateService {

    /** 5-day cumulative FII net outflow beyond this (₹ Cr) flags FLOW_PRESSURE. */
    private static final double FLOW_PRESSURE_THRESHOLD_CR = 10_000;

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final MacroSnapshotRepository macroRepo;
    private final MacroSeriesService macroSeriesService;
    private final YieldCurveService yieldCurveService;
    private final FiiDiiFlowProvider fiiDiiFlowProvider;

    // ── Legacy config fallbacks (used only when a series has no rows yet) ──────

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

    /** A resolved macro figure plus the date it is as-of (null when unresolved). */
    private record Resolved(BigDecimal value, LocalDate asOf) {
        static final Resolved EMPTY = new Resolved(null, null);
    }

    /**
     * Fetch and persist today's macro snapshot from {@code macro_series} (config
     * fallback). Idempotent: an existing snapshot for today is replaced, carrying
     * over any FII/DII flows the 19:00 job already wrote.
     */
    public MacroSnapshot fetchAndPersistDaily() {
        LocalDate today = LocalDate.now();
        List<String> gaps = new ArrayList<>();

        Resolved repo = resolve(MacroSeriesCode.REPO_RATE, configRepoRate, parseDate(configRepoRateAsOf), today, gaps);
        Resolved cpi = resolve(MacroSeriesCode.CPI_HEADLINE, configCpiYoY, parseMonthEnd(configCpiAsOf), today, gaps);
        Resolved gsec3m = resolve(MacroSeriesCode.GSEC_3M, yieldCurveService.gsec3m(), null, today, gaps);
        Resolved gsec1y = resolve(MacroSeriesCode.GSEC_1Y, yieldCurveService.gsec1y(), null, today, gaps);
        Resolved gsec5y = resolve(MacroSeriesCode.GSEC_5Y, yieldCurveService.gsec5y(), null, today, gaps);
        Resolved gsec10y = resolve(MacroSeriesCode.GSEC_10Y,
                configGsecYield10Y != null ? configGsecYield10Y : yieldCurveService.gsec10y(),
                parseDate(configGsecYieldAsOf), today, gaps);
        Resolved usdInr = resolve(MacroSeriesCode.USD_INR, null, null, today, gaps);
        Resolved indiaVix = resolve(MacroSeriesCode.INDIA_VIX, null, null, today, gaps);

        // GDP stays config-only for now (no automated MOSPI GDP series yet).
        BigDecimal gdpGrowth = configGdpGrowth;
        LocalDate gdpAsOf = parseDate(configGdpAsOf);
        if (gdpGrowth == null) gaps.add("MISSING:GDP_GROWTH");

        BigDecimal slope = (gsec3m.value() != null && gsec10y.value() != null)
                ? gsec10y.value().subtract(gsec3m.value())
                : yieldCurveService.slope10y3m();

        MacroSnapshot snapshot = MacroSnapshot.builder()
                .snapshotDate(today)
                .repoRate(repo.value())
                .repoRateAsOf(repo.asOf())
                .cpiYoY(cpi.value())
                .cpiAsOf(cpi.asOf() != null ? MONTH_FMT.format(cpi.asOf()) : configCpiAsOf)
                .gsec3m(gsec3m.value())
                .gsec1y(gsec1y.value())
                .gsec5y(gsec5y.value())
                .gsecYield10Y(gsec10y.value())
                .gsecYieldAsOf(gsec10y.asOf())
                .curveSlope10y3m(slope)
                .gdpGrowth(gdpGrowth)
                .gdpAsOf(gdpAsOf)
                .usdInr(usdInr.value())
                .usdInrFetchedAt(usdInr.value() != null ? LocalDateTime.now() : null)
                .indiaVix(indiaVix.value())
                .indiaVixFetchedAt(indiaVix.value() != null ? LocalDateTime.now() : null)
                .dataQualityNotes(gaps.isEmpty() ? null : String.join("; ", gaps))
                .build();

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
     * Resolves a macro figure: prefer the latest {@code macro_series} observation
     * (noting STALE when past its SLA), else the config fallback (noting
     * CONFIG_FALLBACK), else MISSING.
     */
    private Resolved resolve(MacroSeriesCode code, BigDecimal configValue, LocalDate configAsOf,
                             LocalDate today, List<String> gaps) {
        Optional<MacroSeries> obs = macroSeriesService.latestObs(code);
        if (obs.isPresent()) {
            MacroSeries s = obs.get();
            if (java.time.temporal.ChronoUnit.DAYS.between(s.getObsDate(), today) > code.slaMaxAgeDays()) {
                gaps.add("STALE:" + code.name());
            }
            return new Resolved(s.getValue(), s.getObsDate());
        }
        if (configValue != null) {
            gaps.add("CONFIG_FALLBACK:" + code.name());
            return new Resolved(configValue, configAsOf);
        }
        gaps.add("MISSING:" + code.name());
        return Resolved.EMPTY;
    }

    /**
     * 19:00 IST update: fetch FII/DII flows from NSE into today's snapshot and
     * recompute the 5-day cumulative FII figure. Failure leaves the flow fields
     * null with a staleness note — consumers are null-tolerant.
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

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (Exception _) { return null; }
    }

    private LocalDate parseMonthEnd(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.YearMonth.parse(value, MONTH_FMT).atEndOfMonth();
        } catch (Exception _) {
            return null;
        }
    }

    /** Get the latest macro snapshot; refresh it when stale (not from today). */
    public Optional<MacroSnapshot> getLatest() {
        Optional<MacroSnapshot> latestOpt = macroRepo.findTopByOrderBySnapshotDateDesc();
        if (latestOpt.isPresent() && latestOpt.get().getSnapshotDate().equals(LocalDate.now())) {
            return latestOpt;
        }
        return Optional.of(fetchAndPersistDaily());
    }

    /**
     * Sharpe numerator: annualizedReturn − R_f, using the latest snapshot's 10Y
     * G-sec yield as the risk-free rate. Null when R_f is missing.
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
