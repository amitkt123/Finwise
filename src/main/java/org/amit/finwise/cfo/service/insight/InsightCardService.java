package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.VarBacktestReport;
import org.amit.finwise.cfo.model.VolForecast;
import org.amit.finwise.cfo.service.InsightEvaluationService.CalibrationRow;
import org.amit.finwise.cfo.service.analytics.FactorModelService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.VarBacktestService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The insight-card generator catalog (Honest-Insights Phase B2).
 *
 * <p>One generator per insight in the assessment, each pulling from an existing analytics
 * service and formatting every number <b>in Java</b> — the LLM never sees a figure it could
 * restate incorrectly. Each generator is independently testable (the package-private
 * {@code *Cards} methods take the already-computed engine record) and degrades to "skip"
 * (returns nothing) when its source is empty.
 *
 * <p>{@link #generate(String)} runs the catalog and returns the cards ordered by severity
 * (ALERT → INFO). When no source produces a card it returns an empty list, and the renderer
 * shows an explicit insufficient-data note rather than a fabricated number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightCardService {

    private final PortfolioRiskService portfolioRiskService;
    private final FactorModelService factorModelService;
    private final VarBacktestService varBacktestService;
    private final ConfidenceCalibrationService calibrationService;

    /** %RC at/above which trimming a single contributor becomes an ACTION rather than WATCH. */
    private static final double CONCENTRATED_RC = 0.25;
    /** Sector HHI above which sector concentration is flagged. */
    private static final double SECTOR_HHI_ALERT = 0.30;

    /**
     * Build the full card set for a user, ordered highest-severity first. Every generator is
     * wrapped so one failing source never aborts the brief; a failing generator simply
     * contributes no cards.
     */
    public List<InsightCard> generate(String userId) {
        List<InsightCard> cards = new ArrayList<>();

        Optional<RiskDecomposition> rd = safe(() -> portfolioRiskService.compute(userId));
        rd.ifPresent(r -> {
            cards.addAll(safeList(() -> riskBudgetCards(r)));
            concentrationCard(r).ifPresent(cards::add);
        });

        safe(() -> portfolioRiskService.forwardRisk(userId))
                .flatMap(this::volRegimeCard).ifPresent(cards::add);

        safe(() -> factorModelService.compute(userId))
                .flatMap(this::factorTiltCard).ifPresent(cards::add);

        List<VarBacktestReport> reports = safeList(() -> varBacktestService.backtest(userId));
        varBacktestCard(reports, rd.orElse(null)).ifPresent(cards::add);

        cards.sort(Comparator.comparingInt((InsightCard c) -> c.severity().ordinal()).reversed());
        return calibrate(cards);
    }

    // ── Phase C: calibrated confidence ───────────────────────────────────────────

    /**
     * Replace each card's self-reported confidence with one anchored to the live track record,
     * and attach the scoreboard line. Cards are Java-rendered and carry no originating provider
     * or horizon, so they are calibrated against the <em>system-wide</em> cohort (null provider,
     * null horizon) — the overall hit-rate the engine's directional calls have earned. The
     * scoreboard is fetched once per brief; an empty/unavailable scoreboard leaves cards with
     * their raw confidence and no track-record line.
     */
    private List<InsightCard> calibrate(List<InsightCard> cards) {
        List<CalibrationRow> report = safeReport();
        ConfidenceCalibrationService.Cohort cohort = calibrationService.cohort(report, null, null);
        if (cohort.n() == 0) return cards;   // no scored calls yet → nothing to calibrate against

        String trackRecord = calibrationService.trackRecord(cohort);
        return cards.stream().map(c -> InsightCard.builder(c.id(), c.category(), c.severity())
                        .title(c.title())
                        .actionVerb(c.actionVerb())
                        .symbol(c.symbol())
                        .computations(c.computations())
                        .caveats(c.caveats())
                        .rawConfidence(c.rawConfidence())
                        .calibratedConfidence(calibrationService.calibrate(c.rawConfidence(), cohort))
                        .trackRecord(trackRecord)
                        .narrative(c.narrative())
                        .build())
                .toList();
    }

    private List<CalibrationRow> safeReport() {
        try {
            return calibrationService.report();
        } catch (RuntimeException e) {
            log.warn("[InsightCards] calibration scoreboard unavailable: {}", e.getMessage());
            return List.of();
        }
    }

    // ── RISK_BUDGET ─────────────────────────────────────────────────────────────

    /**
     * One card per top risk contributor: who is eating the portfolio's volatility budget and
     * by how much (MCR/CCR/%RC). The single largest contributor gets a "trim" action when its
     * %RC clears {@link #CONCENTRATED_RC}; the rest are WATCH/INFO context.
     */
    List<InsightCard> riskBudgetCards(RiskDecomposition rd) {
        List<InsightCard> out = new ArrayList<>();
        if (rd.riskContributors() == null || rd.riskContributors().isEmpty()) return out;

        String window = windowOf(rd);
        List<RiskDecomposition.RiskContributor> top = rd.riskContributors().stream().limit(3).toList();
        boolean first = true;
        for (RiskDecomposition.RiskContributor c : top) {
            double pct = c.percentContributionToRisk();
            boolean action = first && pct >= CONCENTRATED_RC;
            InsightCard.Severity sev = action ? InsightCard.Severity.ACTION
                    : (pct >= CONCENTRATED_RC ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO);

            List<Computation> comps = List.of(
                    new Computation("% of portfolio volatility",
                            String.format("%.1f%%", pct * 100),
                            "CCRᵢ / σ_p (Euler risk decomposition)",
                            "value-weighted daily returns", window),
                    new Computation("Market-value weight", String.format("%.1f%%", c.weight() * 100),
                            "wᵢ", "holdings × last price", window),
                    new Computation("Beta vs Nifty 50", String.format("%.2f", c.beta()),
                            "OLS vs Nifty 50", "aligned daily returns", window),
                    new Computation("Marginal contribution to risk",
                            String.format("%.4f", c.marginalContributionToRisk()),
                            "(Σw)ᵢ / σ_p", "covariance matrix", window));

            String title = action
                    ? String.format("Trim %s — top risk contributor (%.0f%% of vol on %.0f%% weight)",
                            c.symbol(), pct * 100, c.weight() * 100)
                    : String.format("%s contributes %.0f%% of portfolio volatility", c.symbol(), pct * 100);

            out.add(InsightCard.builder("risk-budget-" + c.symbol(),
                            InsightCard.Category.RISK_BUDGET, sev)
                    .title(title)
                    .symbol(c.symbol())
                    .actionVerb(action ? "trim" : null)
                    .computations(comps)
                    .caveats(riskCaveats(rd))
                    .rawConfidence(Math.min(0.85, 0.45 + pct))   // stronger concentration → firmer call
                    .build());
            first = false;
        }
        return out;
    }

    // ── CONCENTRATION ───────────────────────────────────────────────────────────

    /** Effective number of bets + name/sector HHI — how diversified the book really is. */
    Optional<InsightCard> concentrationCard(RiskDecomposition rd) {
        if (Double.isNaN(rd.effectiveNumberOfBets())) return Optional.empty();
        String window = windowOf(rd);
        boolean sectorConcentrated = rd.sectorHHI() >= SECTOR_HHI_ALERT;
        boolean fewBets = rd.effectiveNumberOfBets() < 5;
        InsightCard.Severity sev = (sectorConcentrated || fewBets)
                ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO;

        List<Computation> comps = List.of(
                new Computation("Effective bets (ENB)", String.format("%.1f", rd.effectiveNumberOfBets()),
                        "1 / Σ pᵢ² on risk contributions", "%RC vector", window),
                new Computation("Diversification ratio", String.format("%.2f", rd.diversificationRatio()),
                        "(Σ wᵢ σᵢ) / σ_p", "per-name vol + portfolio vol", window),
                new Computation("Name HHI", String.format("%.3f", rd.nameHHI()),
                        "Σ wᵢ²", "market-value weights", window),
                new Computation("Sector HHI", String.format("%.3f", rd.sectorHHI()),
                        "Σ (sector_w)²", "sector-grouped weights", window));

        String title = sectorConcentrated
                ? String.format("Sector-concentrated: %.0f effective bets, sector HHI %.2f",
                        rd.effectiveNumberOfBets(), rd.sectorHHI())
                : String.format("Diversification: %.1f effective bets across the book",
                        rd.effectiveNumberOfBets());

        return Optional.of(InsightCard.builder("concentration",
                        InsightCard.Category.CONCENTRATION, sev)
                .title(title)
                .computations(comps)
                .caveats(riskCaveats(rd))
                .rawConfidence(0.7)
                .build());
    }

    // ── VOL_REGIME ──────────────────────────────────────────────────────────────

    /** Forward volatility regime from the GARCH/EWMA forecast. */
    Optional<InsightCard> volRegimeCard(VolForecast vf) {
        if (vf == null || Double.isNaN(vf.annualizedVol())) return Optional.empty();

        boolean meanReverting = vf.isGarch() && !Double.isNaN(vf.longRunDailyVol());
        boolean elevated = meanReverting && vf.conditionalDailyVol() > vf.longRunDailyVol();
        InsightCard.Severity sev = elevated ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO;

        List<Computation> comps = new ArrayList<>(List.of(
                new Computation("Forward annualized vol", String.format("%.2f%%", vf.annualizedVol() * 100),
                        vf.method() + " conditional σ × √252", "de-meaned daily returns",
                        vf.observations() + " obs"),
                new Computation("1-day conditional vol", String.format("%.3f%%", vf.conditionalDailyVol() * 100),
                        vf.method() + " one-step-ahead", "de-meaned daily returns", vf.observations() + " obs"),
                new Computation("10-day vol", String.format("%.2f%%", vf.tenDayVol() * 100),
                        "√Σ conditional variances over 10d", "forecast path", vf.observations() + " obs")));
        if (meanReverting) {
            comps.add(new Computation("Long-run daily vol", String.format("%.3f%%", vf.longRunDailyVol() * 100),
                    "√(ω/(1−α−β))", "GARCH parameters", vf.observations() + " obs"));
            comps.add(Computation.of("Persistence (α+β)", String.format("%.2f", vf.persistence())));
        }

        String title = elevated
                ? String.format("Vol regime elevated: %.2f%% annualized, above long-run", vf.annualizedVol() * 100)
                : String.format("Forward vol %.2f%% annualized (%s)", vf.annualizedVol() * 100, vf.method());

        List<String> caveats = vf.notes() == null || vf.notes().isEmpty()
                ? List.of("Conditional forecast; assumes the fitted process holds out-of-sample")
                : vf.notes();

        return Optional.of(InsightCard.builder("vol-regime", InsightCard.Category.VOL_REGIME, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(vf.isGarch() ? 0.7 : 0.55)
                .build());
    }

    // ── FACTOR_TILT ─────────────────────────────────────────────────────────────

    /** Systematic vs idiosyncratic split and which factor dominates the systematic variance. */
    Optional<InsightCard> factorTiltCard(FactorRiskReport fr) {
        if (fr.portfolioFactorBetas() == null || fr.portfolioFactorBetas().isEmpty()) return Optional.empty();
        String window = fr.seriesFrom() + " → " + fr.seriesTo();

        String betas = fr.portfolioFactorBetas().entrySet().stream()
                .map(e -> String.format("%s β=%.2f", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + ", " + b).orElse("—");

        List<Computation> comps = new ArrayList<>();
        comps.add(new Computation("Portfolio factor exposures", betas,
                "Σ wᵢ βᵢ per factor", "per-holding OLS betas", window));
        if (!Double.isNaN(fr.totalVolAnnualized())) {
            comps.add(new Computation("Systematic share of variance",
                    String.format("%.0f%%", fr.systematicSharePct()),
                    "systematic variance / model total", "factor covariance", window));
            String topFactor = fr.factorVarianceSharePct().entrySet().stream()
                    .max(java.util.Map.Entry.comparingByValue())
                    .map(e -> String.format("%s %.0f%%", e.getKey(), e.getValue())).orElse("—");
            comps.add(Computation.of("Largest systematic factor", topFactor));
        }

        InsightCard.Severity sev = !Double.isNaN(fr.totalVolAnnualized()) && fr.systematicSharePct() >= 80
                ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO;

        List<String> caveats = new ArrayList<>();
        if (fr.isLowConfidence()) caveats.add("LOW_CONFIDENCE factor estimates");
        if (fr.dataQualityNotes() != null) caveats.addAll(fr.dataQualityNotes());
        caveats.add("t-stats are HAC-naive (Newey-West correction is Phase D)");

        return Optional.of(InsightCard.builder("factor-tilt", InsightCard.Category.FACTOR_TILT, sev)
                .title(Double.isNaN(fr.totalVolAnnualized())
                        ? "Factor exposures: " + betas
                        : String.format("%.0f%% of risk is systematic; %s", fr.systematicSharePct(), betas))
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(fr.isLowConfidence() ? 0.45 : 0.65)
                .build());
    }

    // ── VAR_BACKTEST ────────────────────────────────────────────────────────────

    /**
     * Coverage-test verdict for the VaR the engine reports (Phase A output). When the live
     * risk decomposition is available the validated Cornish-Fisher VaR figures are shown
     * alongside the breach test, so the card states both the number and whether it holds up.
     */
    Optional<InsightCard> varBacktestCard(List<VarBacktestReport> reports, RiskDecomposition rd) {
        if (reports == null || reports.isEmpty()) return Optional.empty();

        List<Computation> comps = new ArrayList<>();
        // The figures being validated come verbatim from the engine record.
        if (rd != null) {
            comps.add(new Computation("1-Day VaR 95% (Cornish-Fisher)",
                    String.format("₹%.0f", rd.var95CornishFisher()),
                    "z·σ_p,daily·V, Cornish-Fisher z", "value-weighted daily returns", windowOf(rd)));
            comps.add(new Computation("Expected Shortfall (CVaR 95%)",
                    String.format("₹%.0f", rd.cvar95()),
                    "mean loss beyond VaR95 × V", "value-weighted daily returns", windowOf(rd)));
        }

        boolean anyReject = false, anyCluster = false;
        for (VarBacktestReport r : reports) {
            anyReject |= r.kupiecReject();
            anyCluster |= r.clusteringDetected();
            comps.add(new Computation(
                    String.format("%.0f%% VaR coverage", (1 - r.confidenceLevel()) * 100),
                    String.format("%d breaches / %dd (%.2f%% vs %.2f%% expected)",
                            r.breaches(), r.observations(), r.actualBreachRate() * 100,
                            r.expectedBreachRate() * 100),
                    String.format("Kupiec p=%.3f%s, Christoffersen p=%.3f%s",
                            r.kupiecPValue(), r.kupiecReject() ? " REJECT" : "",
                            r.christoffersenPValue(), r.clusteringDetected() ? " CLUSTERED" : ""),
                    r.method() + " VaR, rolling window=" + r.window() + "d",
                    r.observations() + " out-of-sample days"));
        }

        InsightCard.Severity sev = anyReject ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO;
        String verdict = reports.getFirst().verdict();

        return Optional.of(InsightCard.builder("var-backtest", InsightCard.Category.VAR_BACKTEST, sev)
                .title("VaR backtest: " + verdict)
                .computations(comps)
                .caveats(List.of(
                        "Rolling out-of-sample; breach = realized return < −VaR_t",
                        "Kupiec tests average coverage (χ²₁); Christoffersen tests breach independence (χ²₁)"))
                .rawConfidence(anyReject ? 0.4 : (anyCluster ? 0.55 : 0.75))
                .build());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static List<String> riskCaveats(RiskDecomposition rd) {
        List<String> caveats = new ArrayList<>();
        if (rd.isLowConfidence()) {
            caveats.add(String.format("LOW_CONFIDENCE: %.1f%% of value excluded for short history",
                    rd.excludedWeightPct() * 100));
        }
        if (rd.dataQualityNotes() != null) caveats.addAll(rd.dataQualityNotes());
        return caveats;
    }

    private static String windowOf(RiskDecomposition rd) {
        return String.format("%s → %s, %dd", rd.seriesFrom(), rd.seriesTo(), rd.observationCount());
    }

    private <T> Optional<T> safe(java.util.function.Supplier<Optional<T>> supplier) {
        try {
            Optional<T> v = supplier.get();
            return v == null ? Optional.empty() : v;
        } catch (RuntimeException e) {
            log.warn("[InsightCards] generator source failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private <T> List<T> safeList(java.util.function.Supplier<List<T>> supplier) {
        try {
            List<T> v = supplier.get();
            return v == null ? List.of() : v;
        } catch (RuntimeException e) {
            log.warn("[InsightCards] generator source failed: {}", e.getMessage());
            return List.of();
        }
    }
}
