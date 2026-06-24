package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AttributionReport;
import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.model.LiquidityReport;
import org.amit.finwise.cfo.model.LookThroughResult;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.VarBacktestReport;
import org.amit.finwise.cfo.model.VolForecast;
import org.amit.finwise.cfo.repository.DismissedInsightCardRepository;
import org.amit.finwise.cfo.service.InsightEvaluationService.CalibrationRow;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.FactorModelService;
import org.amit.finwise.cfo.service.analytics.LiquidityService;
import org.amit.finwise.cfo.service.analytics.LookThroughService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService.TwrrResult;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.StressScenarioService;
import org.amit.finwise.cfo.service.analytics.StressScenarioService.StressResult;
import org.amit.finwise.cfo.service.analytics.TradingCostService;
import org.amit.finwise.cfo.service.analytics.VarBacktestService;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.MonteCarloGoalService;
import org.amit.finwise.investment.service.TaxHarvestingService;
import org.amit.finwise.investment.service.TaxHarvestingService.HarvestPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final StressScenarioService stressScenarioService;
    private final ConfidenceCalibrationService calibrationService;
    private final DismissedInsightCardRepository dismissedRepo;
    private final LiquidityService liquidityService;
    private final TradingCostService tradingCostService;
    private final PortfolioPerformanceService performanceService;
    private final AttributionService attributionService;
    private final TaxHarvestingService taxHarvestingService;
    private final MonteCarloGoalService monteCarloGoalService;
    private final FinancialGoalRepository goalRepository;
    private final LookThroughService lookThroughService;
    private final StockIntelligenceService stockIntelligenceService;

    /** %RC at/above which trimming a single contributor becomes an ACTION rather than WATCH. */
    private static final double CONCENTRATED_RC = 0.25;
    /** Normal z for the 95% VaR — used to recover portfolio value V from VaR95 = z·σ_p·V. */
    private static final double Z95 = 1.645;
    /** Sector HHI above which sector concentration is flagged. */
    private static final double SECTOR_HHI_ALERT = 0.30;
    /** Worst-case stress loss (fraction of book) at/above which the stress card is ALERT / WATCH. */
    private static final double STRESS_ALERT = 0.15;
    private static final double STRESS_WATCH = 0.08;
    /** Information ratio above which active management reads as genuine skill, not noise. */
    private static final double SKILL_IR = 0.5;
    /** Goal success probability below which funding is ALERT / WATCH. */
    private static final double GOAL_ALERT_PROB = 0.50;
    private static final double GOAL_WATCH_PROB = 0.75;
    /** MF look-through coverage below which effective HHIs are note-only, not model-fed. */
    private static final double LOOKTHROUGH_MIN_COVERAGE = 0.70;
    /** Marginal-add hypothetical weight used by the deep-dive PortfolioFit (5%). */
    private static final double MARGINAL_ADD_WEIGHT = 0.05;

    /**
     * Build the full card set for a user, ordered highest-severity first. Every generator is
     * wrapped so one failing source never aborts the brief; a failing generator simply
     * contributes no cards.
     */
    public List<InsightCard> generate(String userId) {
        List<InsightCard> cards = new ArrayList<>();

        Optional<RiskDecomposition> rd = safe(() -> portfolioRiskService.compute(userId));
        rd.ifPresent(r -> {
            // Phase F: per-name spreads feed the impact term of the trim's cost estimate.
            // Missing/unavailable liquidity → empty map → impact is treated as zero, never faked.
            Map<String, Double> spreadBps = safe(() -> liquidityService.compute(userId, r.var95Parametric()))
                    .map(LiquidityReport::spreadBps).orElse(Map.of());
            cards.addAll(safeList(() -> riskBudgetCards(r, spreadBps)));
            concentrationCard(r).ifPresent(cards::add);
        });

        safe(() -> portfolioRiskService.forwardRisk(userId))
                .flatMap(this::volRegimeCard).ifPresent(cards::add);

        safe(() -> factorModelService.compute(userId))
                .flatMap(this::factorTiltCard).ifPresent(cards::add);

        List<VarBacktestReport> reports = safeList(() -> varBacktestService.backtest(userId));
        varBacktestCard(reports, rd.orElse(null)).ifPresent(cards::add);

        stressCard(safeList(() -> stressScenarioService.stress(userId))).ifPresent(cards::add);

        safe(() -> performanceService.computeTwrr(userId))
                .flatMap(this::skillVerdictCard).ifPresent(cards::add);

        safe(() -> attributionService.compute(userId))
                .flatMap(this::attributionCard).ifPresent(cards::add);

        taxHarvestCard(safeGet(() -> taxHarvestingService.suggest(userId))).ifPresent(cards::add);

        cards.addAll(safeList(() -> goalFundingCards(userId)));

        lookThroughCard(safeGet(() -> lookThroughService.compute(userId).orElse(null))).ifPresent(cards::add);

        cards.sort(Comparator.comparingInt((InsightCard c) -> c.severity().ordinal()).reversed());
        Set<String> dismissed = dismissedRepo.findByUserId(userId).stream()
                .map(org.amit.finwise.cfo.model.DismissedInsightCard::getCardId)
                .collect(Collectors.toSet());
        return calibrate(cards).stream()
                .filter(c -> !dismissed.contains(c.id()))
                .toList();
    }

    /**
     * On-demand marginal-add analysis for a candidate symbol (Phase B2 {@code MARGINAL_ADD}).
     * Not part of the daily {@link #generate(String)} loop — a brief has no candidate, and
     * computing a deep-dive for an arbitrary name would manufacture relevance. Reachable via the
     * REST layer so a "should I add X?" question gets a Java-rendered, cost-honest answer.
     */
    public Optional<InsightCard> marginalAddCard(String userId, String symbol) {
        StockDeepDive dive = safeGet(() -> stockIntelligenceService.analyze(symbol, userId));
        return dive == null ? Optional.empty() : marginalAddCard(symbol, dive.portfolioFit());
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
     * %RC clears {@link #CONCENTRATED_RC} — but only if the trim is worth it: Phase F sizes the
     * trim, prices its all-in cost via {@link TradingCostService}, and suppresses the trade
     * (downgrading the card to WATCH) when the daily VaR95 it would remove is smaller than the
     * cost. The rest of the top contributors are WATCH/INFO context.
     */
    List<InsightCard> riskBudgetCards(RiskDecomposition rd, Map<String, Double> spreadBps) {
        List<InsightCard> out = new ArrayList<>();
        if (rd.riskContributors() == null || rd.riskContributors().isEmpty()) return out;

        String window = windowOf(rd);
        double portfolioValue = portfolioValue(rd);   // recovered from VaR95 = z·σ_p·V
        List<RiskDecomposition.RiskContributor> top = rd.riskContributors().stream().limit(3).toList();
        boolean first = true;
        for (RiskDecomposition.RiskContributor c : top) {
            double pct = c.percentContributionToRisk();
            boolean wantTrim = first && pct >= CONCENTRATED_RC;

            List<Computation> comps = new ArrayList<>(List.of(
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
                            "(Σw)ᵢ / σ_p", "covariance matrix", window)));

            // Phase F: cost-aware trim economics. Null when no trim is wanted or V is unrecoverable.
            TrimEconomics trim = wantTrim ? trimEconomics(c, portfolioValue, spreadBps, window) : null;
            boolean action = trim != null && trim.worthwhile();

            InsightCard.Severity sev = action ? InsightCard.Severity.ACTION
                    : (pct >= CONCENTRATED_RC ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO);

            List<String> caveats = new ArrayList<>(riskCaveats(rd));
            String title;
            if (action) {
                comps.addAll(trim.computations());
                title = String.format("Trim %s by ₹%,.0f — net VaR95 benefit ₹%,.0f after ₹%,.0f costs",
                        c.symbol(), trim.grossTrim(), trim.netBenefit(), trim.cost());
            } else if (trim != null) {
                // Trim wanted but the cost outweighs the risk benefit: keep the contributor card
                // for context, drop the trade recommendation, and say why (no fabricated upside).
                comps.addAll(trim.computations());
                caveats.add(String.format(
                        "Trim suppressed: est ₹%,.0f cost exceeds the ₹%,.0f daily VaR95 it would remove",
                        trim.cost(), trim.grossBenefit()));
                title = String.format("%s contributes %.0f%% of volatility — trim not cost-effective",
                        c.symbol(), pct * 100);
            } else {
                title = String.format("%s contributes %.0f%% of portfolio volatility", c.symbol(), pct * 100);
            }

            out.add(InsightCard.builder("risk-budget-" + c.symbol(),
                            InsightCard.Category.RISK_BUDGET, sev)
                    .title(title)
                    .symbol(c.symbol())
                    .actionVerb(action ? "trim" : null)
                    .computations(comps)
                    .caveats(caveats)
                    .rawConfidence(Math.min(0.85, 0.45 + pct))   // stronger concentration → firmer call
                    .build());
            first = false;
        }
        return out;
    }

    /** Portfolio value V recovered from the engine's VaR95 = z·σ_p,daily·V identity. */
    private static double portfolioValue(RiskDecomposition rd) {
        double denom = Z95 * rd.dailyVolatility();
        return denom > 0 ? rd.var95Parametric() / denom : Double.NaN;
    }

    /**
     * Size the trim that brings a contributor's %RC back to {@link #CONCENTRATED_RC}, price its
     * sell-leg cost (statutory + half-spread impact), and value the risk it removes. Since
     * {@code dσ_p/dwᵢ = MCRᵢ}, trimming Δw of value V removes a daily VaR95 of {@code z·MCRᵢ·trim}.
     * Returns null when V is unrecoverable or the trim rounds to nothing.
     */
    private TrimEconomics trimEconomics(RiskDecomposition.RiskContributor c, double portfolioValue,
                                        Map<String, Double> spreadBps, String window) {
        if (!Double.isFinite(portfolioValue) || portfolioValue <= 0) return null;
        double pct = c.percentContributionToRisk();
        double positionValue = c.weight() * portfolioValue;
        double trimFraction = Math.min(1.0, Math.max(0.0, (pct - CONCENTRATED_RC) / pct));
        double grossTrim = trimFraction * positionValue;
        if (grossTrim <= 0) return null;

        double spreadFraction = spreadBps.getOrDefault(c.symbol(), 0.0) / 10_000.0;
        TradingCostService.CostEstimate cost = tradingCostService.estimate(
                TradingCostService.Side.SELL, grossTrim, spreadFraction);

        double grossBenefit = Z95 * c.marginalContributionToRisk() * grossTrim;
        double netBenefit = grossBenefit - cost.total();
        double rcReductionPctPts = (pct - CONCENTRATED_RC) * 100;

        List<Computation> comps = List.of(
                new Computation("Suggested trim",
                        String.format("₹%,.0f (%.0f%% of the ₹%,.0f position)",
                                grossTrim, trimFraction * 100, positionValue),
                        "trim to bring %RC down to the 25% target", "wᵢ × portfolio value", window),
                new Computation("Est. trading cost",
                        String.format("₹%,.0f (%.1f bps)", cost.total(), cost.totalBps()),
                        cost.breakdown(),
                        "STT 0.1% (delivery) + NSE txn + SEBI + 18% GST + ½-spread impact",
                        "current statutory schedule"),
                new Computation("Daily VaR95 reduction (net of cost)",
                        String.format("₹%,.0f gross − ₹%,.0f cost = ₹%,.0f", grossBenefit, cost.total(), netBenefit),
                        "z·MCRᵢ·trim", "MCR from covariance matrix", window),
                new Computation("Targeted risk-contribution reduction",
                        String.format("%.0f%% → 25%% (−%.1f pp)", pct * 100, rcReductionPctPts),
                        "proportional to trim fraction", "%RC vector", window));

        return new TrimEconomics(grossTrim, cost.total(), grossBenefit, netBenefit, comps);
    }

    /** Sized trim plus its priced cost and the daily VaR95 it removes (Phase F). */
    private record TrimEconomics(double grossTrim, double cost, double grossBenefit,
                                 double netBenefit, List<Computation> computations) {
        /** Worth recommending only when the risk it removes outweighs the round-trip cost. */
        boolean worthwhile() {
            return netBenefit > 0;
        }
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
        caveats.add("t-stats use Newey-West HAC (heteroskedasticity- and autocorrelation-consistent)");

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

    // ── STRESS ──────────────────────────────────────────────────────────────────

    /**
     * Named historical shock replays applied to the current book (Phase E). One computation row
     * per scenario stating the factor-model P&L and the beta-only cross-check; severity is driven
     * by the worst-case loss as a share of the book. The card is informational (no per-symbol
     * action) — it sizes tail risk, it does not recommend a trade.
     */
    Optional<InsightCard> stressCard(List<StressResult> results) {
        if (results == null || results.isEmpty()) return Optional.empty();

        StressResult worst = results.getFirst();   // service returns worst factor-model loss first

        List<Computation> comps = new ArrayList<>();
        for (StressResult r : results) {
            comps.add(new Computation(
                    r.label(),
                    String.format("Nifty %+.1f%% → est P&L ₹%,.0f (β-only cross-check ₹%,.0f)",
                            r.niftyShockPct(), r.factorModelPnl(), r.betaOnlyPnl()),
                    "Σ βf·shockf·V (factor model) vs β_mkt·shock·V (cross-check)",
                    String.format("β_mkt=%.2f, V=₹%,.0f", r.betaMkt(), r.portfolioValue()),
                    r.scenario()));
        }

        double worstLossPct = worst.lossPctOfValue();          // negative = loss
        double worstLossMag = Math.abs(worstLossPct);
        InsightCard.Severity sev = worstLossMag >= STRESS_ALERT ? InsightCard.Severity.ALERT
                : worstLossMag >= STRESS_WATCH ? InsightCard.Severity.WATCH
                : InsightCard.Severity.INFO;

        String title = String.format("Stress: worst case %s → ₹%,.0f (%.1f%% of book)",
                worst.label(), worst.factorModelPnl(), worstLossPct * 100);

        List<String> caveats = new ArrayList<>(List.of(
                "Historical factor-return replay applied to current betas estimated on the factor-model window",
                "Factor-model and beta-only estimates diverge by the sector/size tilt — the gap is informative"));
        if (worst.notes() != null) caveats.addAll(worst.notes());

        return Optional.of(InsightCard.builder("stress", InsightCard.Category.STRESS, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(0.55)
                .build());
    }

    // ── SKILL ─────────────────────────────────────────────────────────────────────

    /**
     * Active-management verdict from the time-weighted return (Phase B2). States the portfolio
     * TWRR against Nifty, the annualized active return, tracking error, and the information
     * ratio — the only honest read of whether active risk has been rewarded. The verdict is
     * Java-authoritative: positive IR above {@link #SKILL_IR} reads as skill, a negative active
     * return reads as lagging, everything else is "in line with the benchmark".
     */
    Optional<InsightCard> skillVerdictCard(TwrrResult t) {
        if (t == null || Double.isNaN(t.twrr())) return Optional.empty();
        String window = t.from() + " → " + t.to();

        boolean benchKnown = !Double.isNaN(t.benchmarkTwrr());
        List<Computation> comps = new ArrayList<>();
        comps.add(new Computation("Portfolio TWRR", String.format("%.2f%%", t.twrr() * 100),
                "time-weighted (sub-period geometric link)", "dated cash-flow buckets",
                window + ", " + t.subPeriods() + " sub-periods"));
        if (benchKnown) {
            comps.add(new Computation("Nifty 50 TWRR", String.format("%.2f%%", t.benchmarkTwrr() * 100),
                    "benchmark over matched sub-periods", "Nifty 50 total return", window));
            comps.add(new Computation("Active return (annualized)",
                    String.format("%+.2f%%", t.activeReturnAnnualized() * 100),
                    "portfolio − benchmark, annualized", "matched sub-period returns", window));
            comps.add(new Computation("Tracking error (annualized)",
                    String.format("%.2f%%", t.trackingErrorAnnualized() * 100),
                    "stdev of active sub-period returns × √annualization", "active return series", window));
            comps.add(new Computation("Information ratio", String.format("%.2f", t.informationRatio()),
                    "active return / tracking error", "annualized active stats", window));
        }

        boolean lagging = benchKnown && t.activeReturnAnnualized() < 0;
        boolean skilled = benchKnown && t.informationRatio() >= SKILL_IR && t.activeReturnAnnualized() > 0;
        InsightCard.Severity sev = lagging ? InsightCard.Severity.WATCH : InsightCard.Severity.INFO;

        String title;
        if (!benchKnown) {
            title = String.format("Portfolio TWRR %.1f%% (no benchmark match)", t.twrr() * 100);
        } else if (skilled) {
            title = String.format("Active skill: %+.1f%% p.a. vs Nifty, IR %.2f",
                    t.activeReturnAnnualized() * 100, t.informationRatio());
        } else if (lagging) {
            title = String.format("Lagging Nifty by %.1f%% p.a. (IR %.2f)",
                    -t.activeReturnAnnualized() * 100, t.informationRatio());
        } else {
            title = String.format("In line with Nifty: %+.1f%% p.a., IR %.2f",
                    t.activeReturnAnnualized() * 100, t.informationRatio());
        }

        List<String> caveats = new ArrayList<>();
        if (Double.isNaN(t.annualizedTwrr())) caveats.add("period < 30 days — annualization withheld");
        if (!benchKnown) caveats.add("Nifty sub-periods unavailable — active stats not computed");
        caveats.add("IR over a single window is a noisy skill estimate, not a guarantee");

        return Optional.of(InsightCard.builder("skill", InsightCard.Category.SKILL, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(benchKnown ? 0.6 : 0.45)
                .build());
    }

    // ── ATTRIBUTION ─────────────────────────────────────────────────────────────

    /**
     * Brinson-Fachler decomposition of the excess return over Nifty (Phase B2): how much of the
     * outperformance came from sector allocation, stock selection, and their interaction, with
     * the linking residual reported rather than absorbed. Informational — it explains the past,
     * it does not recommend a trade.
     */
    Optional<InsightCard> attributionCard(AttributionReport a) {
        if (a == null) return Optional.empty();
        String window = a.windowStart() + " → " + a.windowEnd() + ", " + a.buckets() + " buckets";

        List<Computation> comps = List.of(
                new Computation("Excess return over Nifty", String.format("%+.2f%%", a.excessReturn() * 100),
                        "r_p − r_b (geometric)", "portfolio vs Nifty compounded returns", window),
                new Computation("Allocation effect", String.format("%+.2f%%", a.allocationEffect() * 100),
                        "Σ (w_p−w_b)(r_b,s − r_b), Cariño-linked", "sector weights + benchmark sector returns", window),
                new Computation("Selection effect", String.format("%+.2f%%", a.selectionEffect() * 100),
                        "Σ w_b(r_p,s − r_b,s), Cariño-linked", "in-sector portfolio vs benchmark returns", window),
                new Computation("Interaction effect", String.format("%+.2f%%", a.interactionEffect() * 100),
                        "Σ (w_p−w_b)(r_p,s − r_b,s), Cariño-linked", "sector weight × return differentials", window),
                new Computation("Linking residual", String.format("%+.3f%%", a.residual() * 100),
                        "(alloc+select+interact) − excess", "geometric-linking gap", window));

        InsightCard.Severity sev = InsightCard.Severity.INFO;
        String title = a.headline() != null ? a.headline()
                : String.format("Attribution: %+.1f%% excess (alloc %+.1f%%, select %+.1f%%)",
                        a.excessReturn() * 100, a.allocationEffect() * 100, a.selectionEffect() * 100);

        List<String> caveats = new ArrayList<>();
        if (a.lowConfidence()) caveats.add("LOW_CONFIDENCE: benchmark sector weights are stale (>120d)");
        if (a.dataQualityNotes() != null) caveats.addAll(a.dataQualityNotes());
        caveats.add("benchmark sector weights from quarterly disclosure as of " + a.benchmarkAsOf());

        return Optional.of(InsightCard.builder("attribution", InsightCard.Category.ATTRIBUTION, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(a.lowConfidence() ? 0.45 : 0.6)
                .build());
    }

    // ── TAX ─────────────────────────────────────────────────────────────────────

    /**
     * LTCG-exemption and loss-harvesting plan for the current financial year (Phase B2). The card
     * is an ACTION when there is exemption headroom to realize tax-free gains into; otherwise it
     * is WATCH (a loss/boundary opportunity exists) or skipped. Every ₹ figure is the harvesting
     * service's, formatted in Java — the LLM never restates a tax number.
     */
    Optional<InsightCard> taxHarvestCard(HarvestPlan plan) {
        if (plan == null) return Optional.empty();
        boolean hasExemption = plan.exemptionHarvest() != null && !plan.exemptionHarvest().isEmpty();
        boolean hasLoss = plan.lossHarvest() != null && !plan.lossHarvest().isEmpty();
        boolean hasBoundary = plan.boundaryWait() != null && !plan.boundaryWait().isEmpty();
        if (!hasExemption && !hasLoss && !hasBoundary) return Optional.empty();
        String window = "FY " + plan.fyStart() + " → " + plan.fyEnd();

        List<Computation> comps = new ArrayList<>();
        comps.add(new Computation("LTCG exemption headroom",
                String.format("₹%,.0f", plan.exemptionHeadroom() == null
                        ? 0.0 : plan.exemptionHeadroom().doubleValue()),
                "₹1.25L annual LTCG exemption − gains already realized this FY", "FY realized gains", window));
        if (hasExemption) {
            double taxSaved = plan.exemptionHarvest().stream()
                    .mapToDouble(TaxHarvestingService.HarvestCandidate::taxSavedVsLater).sum();
            comps.add(new Computation("Tax-free gain harvest",
                    String.format("%d lot(s), ₹%,.0f LTCG tax avoided", plan.exemptionHarvest().size(), taxSaved),
                    "realize gains inside the exemption, re-buy to step up cost basis", "long-term lots", window));
        }
        if (hasBoundary) {
            double boundarySaving = plan.boundaryWait().stream()
                    .mapToDouble(TaxHarvestingService.BoundaryCandidate::taxSavingIfWaited).sum();
            comps.add(new Computation("Hold-to-long-term opportunity",
                    String.format("%d lot(s), ₹%,.0f saved if held to 12 months",
                            plan.boundaryWait().size(), boundarySaving),
                    "gain × (STCG − LTCG rate) if sale deferred past the 1-year boundary", "short-term lots", window));
        }
        if (hasLoss) {
            double losses = plan.lossHarvest().stream()
                    .mapToDouble(TaxHarvestingService.LossCandidate::unrealizedLoss).sum();
            comps.add(new Computation("Loss-harvest set-off pool", String.format("₹%,.0f", losses),
                    "realizable losses to offset taxable gains", "underwater lots", window));
        }

        InsightCard.Severity sev = hasExemption ? InsightCard.Severity.ACTION : InsightCard.Severity.WATCH;
        String title = hasExemption
                ? String.format("Harvest ₹%,.0f of exemption headroom before FY-end",
                        plan.exemptionHeadroom() == null ? 0.0 : plan.exemptionHeadroom().doubleValue())
                : hasLoss ? "Tax-loss harvesting opportunity available"
                        : "Hold positions to long-term to cut tax";

        List<String> caveats = new ArrayList<>();
        if (plan.notes() != null) caveats.addAll(plan.notes());
        caveats.add("Rates per current LTCG/STCG schedule; re-buys may attract STT and reset the holding clock");

        return Optional.of(InsightCard.builder("tax-harvest", InsightCard.Category.TAX, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(0.65)
                .build());
    }

    // ── GOAL ────────────────────────────────────────────────────────────────────

    /**
     * One Monte-Carlo funding card per active goal (Phase B2): probability of reaching the
     * inflation-adjusted target at the current SIP, the terminal-corpus fan (P10/P50/P90), and
     * the SIP required for 75% confidence. Severity escalates as the success probability falls.
     * A goal whose simulation is unavailable contributes no card rather than a fabricated number.
     */
    List<InsightCard> goalFundingCards(String userId) {
        List<FinancialGoal> goals = goalRepository.findActiveGoals(userId);
        if (goals == null || goals.isEmpty()) return List.of();

        List<InsightCard> out = new ArrayList<>();
        for (FinancialGoal goal : goals) {
            GoalSimulationResult r = safeGet(() -> monteCarloGoalService.simulate(goal, null));
            if (r == null || Double.isNaN(r.probabilityOfSuccess())) continue;
            String window = r.months() + " months to target";

            List<Computation> comps = new ArrayList<>(List.of(
                    new Computation("Probability of success",
                            String.format("%.0f%%", r.probabilityOfSuccess() * 100),
                            r.mode() + " Monte-Carlo, " + r.paths() + " paths",
                            String.format("SIP ₹%,.0f/mo, μ=%.1f%%, σ=%.1f%%",
                                    r.currentSip(), r.annualDrift() * 100, r.annualVolatility() * 100),
                            window),
                    new Computation("Inflation-adjusted target", String.format("₹%,.0f", r.inflationAdjustedTarget()),
                            "target compounded at the goal's inflation rate", "goal target + horizon", window),
                    new Computation("Terminal corpus fan (P10 / P50 / P90)",
                            String.format("₹%,.0f / ₹%,.0f / ₹%,.0f", r.corpusP10(), r.corpusP50(), r.corpusP90()),
                            "simulated terminal-wealth percentiles", "path distribution", window)));
            if (!Double.isNaN(r.requiredSip75())) {
                comps.add(new Computation("SIP for 75% confidence", String.format("₹%,.0f/mo", r.requiredSip75()),
                        "bisection on success probability", "seeded re-simulation", window));
            }

            InsightCard.Severity sev = r.probabilityOfSuccess() < GOAL_ALERT_PROB ? InsightCard.Severity.ALERT
                    : r.probabilityOfSuccess() < GOAL_WATCH_PROB ? InsightCard.Severity.WATCH
                    : InsightCard.Severity.INFO;

            String title = r.headline() != null ? r.headline()
                    : String.format("%s: %.0f%% funded at current SIP", goal.getName(), r.probabilityOfSuccess() * 100);

            List<String> caveats = new ArrayList<>();
            if (r.lowConfidence()) caveats.add("LOW_CONFIDENCE: drift/vol fell back to planning defaults");
            if (r.notes() != null) caveats.addAll(r.notes());

            out.add(InsightCard.builder("goal-" + goal.getId(), InsightCard.Category.GOAL, sev)
                    .title(title)
                    .computations(comps)
                    .caveats(caveats)
                    .rawConfidence(r.lowConfidence() ? 0.45 : 0.6)
                    .build());
        }
        return out;
    }

    // ── LOOKTHROUGH ───────────────────────────────────────────────────────────────

    /**
     * Mutual-fund look-through (Phase B2): the portfolio's TRUE concentration once funds are
     * exploded into their constituents. Flags the case where direct holdings look diversified but
     * fund overlap pushes the effective HHI up. Effective HHIs are trusted only when MF coverage
     * clears {@link #LOOKTHROUGH_MIN_COVERAGE}; below that the card says so and the unmapped
     * residue is reported rather than silently dropped.
     */
    Optional<InsightCard> lookThroughCard(LookThroughResult lt) {
        if (lt == null || lt.mfWeight() <= 0) return Optional.empty();
        String window = "fund disclosures as used (" + lt.asOfByScheme().size() + " scheme(s))";

        boolean hhiJumped = lt.effectiveNameHHI() > lt.directNameHHI() * 1.15
                || lt.effectiveSectorHHI() > lt.directSectorHHI() * 1.15;
        boolean lowCoverage = lt.mfCoverage() < LOOKTHROUGH_MIN_COVERAGE;

        List<Computation> comps = List.of(
                new Computation("Mutual-fund weight", String.format("%.1f%%", lt.mfWeight() * 100),
                        "Σ portfolio weight in funds", "holdings", window),
                new Computation("Look-through coverage", String.format("%.0f%%", lt.mfCoverage() * 100),
                        "seen-through MF weight ÷ total MF weight", "fund constituent disclosures", window),
                new Computation("Unmapped fund residue", String.format("%.1f%%", lt.unmappedResidue() * 100),
                        "MF weight whose constituents are undisclosed/unresolved", "fund disclosures", window),
                new Computation("Name HHI (direct → effective)",
                        String.format("%.3f → %.3f", lt.directNameHHI(), lt.effectiveNameHHI()),
                        "Σ wᵢ² before vs after explosion", "direct + look-through weights", window),
                new Computation("Sector HHI (direct → effective)",
                        String.format("%.3f → %.3f", lt.directSectorHHI(), lt.effectiveSectorHHI()),
                        "Σ (sector_w)² before vs after explosion", "direct + look-through weights", window));

        InsightCard.Severity sev = (hhiJumped && !lowCoverage) ? InsightCard.Severity.WATCH
                : InsightCard.Severity.INFO;
        String title = lt.headline() != null ? lt.headline()
                : hhiJumped ? String.format("Fund overlap raises true concentration: name HHI %.3f → %.3f",
                        lt.directNameHHI(), lt.effectiveNameHHI())
                : String.format("Look-through: %.0f%% in funds, true name HHI %.3f",
                        lt.mfWeight() * 100, lt.effectiveNameHHI());

        List<String> caveats = new ArrayList<>();
        if (!lt.feedsModel()) caveats.add(String.format(
                "MF coverage %.0f%% < 70%% — effective HHIs are indicative only, not fed to the risk model",
                lt.mfCoverage() * 100));
        if (lt.dataQualityNotes() != null) caveats.addAll(lt.dataQualityNotes());
        caveats.add("Fund factsheets are monthly at best — constituents may have drifted since disclosure");

        return Optional.of(InsightCard.builder("look-through", InsightCard.Category.LOOKTHROUGH, sev)
                .title(title)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(lt.feedsModel() ? 0.6 : 0.45)
                .build());
    }

    // ── MARGINAL_ADD ──────────────────────────────────────────────────────────────

    /**
     * Marginal impact of adding a candidate at a small hypothetical weight (Phase B2): how a 5%
     * position would move portfolio volatility and how correlated the name is to what is already
     * held — the two numbers that decide whether an addition diversifies or doubles down. The
     * deep-dive's verdict label is Java-authoritative; the card states it and shows the working.
     * Skipped when the fit could not be computed (no aligned history).
     */
    Optional<InsightCard> marginalAddCard(String symbol, StockDeepDive.PortfolioFit fit) {
        if (fit == null || Double.isNaN(fit.marginalVolImpact())) return Optional.empty();

        List<Computation> comps = new ArrayList<>(List.of(
                new Computation("Marginal vol impact",
                        String.format("%+.2f%% annualized at a %.0f%% weight",
                                fit.marginalVolImpact() * 100, MARGINAL_ADD_WEIGHT * 100),
                        "Δσ_p from adding the candidate at the hypothetical weight",
                        "covariance of candidate with current book", "aligned daily returns"),
                new Computation("Correlation to portfolio", String.format("%.2f", fit.correlationToPortfolio()),
                        "ρ(candidate, existing portfolio)", "aligned daily returns", "available history"),
                new Computation("Beta vs Nifty 50", String.format("%.2f", fit.betaVsNifty()),
                        "OLS vs Nifty 50", "aligned daily returns", "available history")));
        if (!Double.isNaN(fit.newPortfolioVol())) {
            comps.add(Computation.of("Portfolio vol after add",
                    String.format("%.2f%% annualized", fit.newPortfolioVol() * 100)));
        }

        boolean diversifying = fit.marginalVolImpact() < 0
                || (!Double.isNaN(fit.correlationToPortfolio()) && fit.correlationToPortfolio() < 0.5);
        String verb = switch (fit.verdictLabel() == null ? "" : fit.verdictLabel()) {
            case "INITIATE" -> "add";
            case "AVOID" -> "avoid";
            case "WAIT-FOR-PULLBACK" -> "watch";
            default -> null;   // NEEDS-MORE-DATA → no directional claim
        };
        InsightCard.Severity sev = "add".equals(verb) ? InsightCard.Severity.ACTION
                : (diversifying ? InsightCard.Severity.INFO : InsightCard.Severity.WATCH);

        String title = String.format("Add %s: %s portfolio vol %+.2f%%, ρ=%.2f — %s",
                symbol, diversifying ? "diversifies" : "concentrates",
                fit.marginalVolImpact() * 100, fit.correlationToPortfolio(),
                fit.verdictLabel() == null ? "no verdict" : fit.verdictLabel());

        List<String> caveats = new ArrayList<>();
        if (fit.verdictRationale() != null) caveats.add(fit.verdictRationale());
        caveats.add("Marginal impact assumes a " + (int) (MARGINAL_ADD_WEIGHT * 100)
                + "% funded position; correlations are historical and regime-dependent");

        return Optional.of(InsightCard.builder("marginal-add-" + symbol,
                        InsightCard.Category.MARGINAL_ADD, sev)
                .title(title)
                .symbol(symbol)
                .actionVerb(verb)
                .computations(comps)
                .caveats(caveats)
                .rawConfidence(0.5)
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

    /** For sources that return a plain (possibly null) record rather than an Optional/List. */
    private <T> T safeGet(java.util.function.Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            log.warn("[InsightCards] generator source failed: {}", e.getMessage());
            return null;
        }
    }
}
