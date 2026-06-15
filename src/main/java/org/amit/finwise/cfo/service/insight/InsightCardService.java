package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.*;
import org.amit.finwise.cfo.service.analytics.*;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.MonteCarloGoalService;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.service.TaxHarvestingService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * B2 — generator catalog (all 11 generators).
 *
 * generate(userId) wires every generator, degrades gracefully on missing data,
 * narrates each card via InsightNarrationService, and returns cards sorted ALERT→INFO.
 * Numbers are Java-authored in every generator; the LLM only receives them for narration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightCardService {

    private final PortfolioRiskService portfolioRiskService;
    private final VarBacktestService varBacktestService;
    private final FactorModelService factorModelService;
    private final PortfolioPerformanceService portfolioPerformanceService;
    private final MoneyWeightedReturnService moneyWeightedReturnService;
    private final AttributionService attributionService;
    private final LookThroughService lookThroughService;
    private final TaxHarvestingService taxHarvestingService;
    private final MonteCarloGoalService monteCarloGoalService;
    private final StockIntelligenceService stockIntelligenceService;
    private final FinancialGoalRepository goalRepository;
    private final InvestmentRepository investmentRepository;
    private final InsightNarrationService narrationService;

    // ── Entry point ────────────────────────────────────────────────────────────

    public List<InsightCard> generate(String userId) {
        List<InsightCard> raw = new ArrayList<>();
        raw.addAll(safe(() -> riskBudgetCards(userId)));
        raw.addAll(safe(() -> concentrationCard(userId)));
        raw.addAll(safe(() -> volRegimeCard(userId)));
        raw.addAll(safe(() -> factorTiltCard(userId)));
        raw.addAll(safe(() -> varBacktestCard(userId)));
        raw.addAll(safe(() -> marginalAddCards(userId)));
        raw.addAll(safe(() -> skillVerdictCard(userId)));
        raw.addAll(safe(() -> attributionCard(userId)));
        raw.addAll(safe(() -> taxCard(userId)));
        raw.addAll(safe(() -> goalFundingCards(userId)));
        raw.addAll(safe(() -> lookThroughCard(userId)));

        return raw.stream()
                .map(this::withNarration)
                .sorted(Comparator.comparingInt(c -> c.getSeverity() == InsightCard.Severity.ALERT ? 0 : 1))
                .collect(Collectors.toList());
    }

    // ── G1: Risk Budget ────────────────────────────────────────────────────────

    List<InsightCard> riskBudgetCards(String userId) {
        return portfolioRiskService.compute(userId).map(rd -> {
            if (rd.riskContributors() == null || rd.riskContributors().isEmpty()) {
                return List.of(insufficientDataCard("Risk Budget", InsightCard.Category.RISK));
            }
            RiskDecomposition.RiskContributor top = rd.riskContributors().get(0);
            double pctRisk = top.percentContributionToRisk() * 100;
            List<Computation> numbers = List.of(
                    new Computation(pctRisk, "% Risk Contribution (" + top.symbol() + ")",
                            List.of(top.symbol()), rd.observationCount() + "d", "%"),
                    new Computation(rd.annualizedVolatility() * 100, "Portfolio Annualized Vol",
                            rd.includedSymbols(), rd.observationCount() + "d", "%"),
                    new Computation(rd.portfolioBeta(), "Portfolio Beta vs Nifty 50",
                            List.of("Nifty 50"), rd.observationCount() + "d", "x")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.RISK)
                    .severity(pctRisk > 30 ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Risk Budget — " + top.symbol())
                    .headline(String.format("%s consumes %.2f%% of portfolio risk (β=%.4f, portVol=%.2f%%)",
                            top.symbol(), pctRisk, top.beta(), rd.annualizedVolatility() * 100))
                    .numbers(numbers)
                    .symbol(top.symbol())
                    .build());
        }).orElse(List.of(insufficientDataCard("Risk Budget", InsightCard.Category.RISK)));
    }

    // ── G2: Concentration ──────────────────────────────────────────────────────

    List<InsightCard> concentrationCard(String userId) {
        return portfolioRiskService.compute(userId).map(rd -> {
            if (rd.isLowConfidence()) return List.<InsightCard>of();
            double effBets = rd.effectiveNumberOfBets();
            double hhi = rd.nameHHI() * 100;
            boolean alert = effBets < 5 || hhi > 20;
            List<Computation> numbers = List.of(
                    new Computation(effBets, "Effective Number of Bets",
                            rd.includedSymbols(), null, "x"),
                    new Computation(hhi, "Name HHI",
                            rd.includedSymbols(), null, "%"),
                    new Computation(rd.diversificationRatio(), "Diversification Ratio",
                            rd.includedSymbols(), rd.observationCount() + "d", "x")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.ALLOCATION)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Concentration")
                    .headline(String.format("%.4f effective bets, HHI=%.4f%% — %s",
                            effBets, hhi, alert ? "concentrated" : "diversified"))
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── G3: Vol Regime (GARCH) ─────────────────────────────────────────────────

    List<InsightCard> volRegimeCard(String userId) {
        return portfolioRiskService.forwardRisk(userId).map(vf -> {
            double annVol = vf.annualizedVol() * 100;
            double condDaily = vf.conditionalDailyVol() * 100;
            double tenDay = vf.tenDayVol() * 100;
            boolean alert = vf.annualizedVol() > 0.25;
            List<Computation> numbers = List.of(
                    new Computation(annVol, "GARCH Annualized Vol Forecast",
                            List.of(), vf.observations() + "d", "%"),
                    new Computation(condDaily, "Conditional Daily Vol (1-step)",
                            List.of(), null, "%"),
                    new Computation(tenDay, "10-Day Vol (path-integrated)",
                            List.of(), null, "%")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.VOLATILITY)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Volatility Regime (" + vf.method().name() + ")")
                    .headline(String.format("Forward vol %.4f%% p.a. — %s regime (%s)",
                            annVol, alert ? "elevated" : "normal", vf.method().name()))
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── G4: Factor Tilt ────────────────────────────────────────────────────────

    List<InsightCard> factorTiltCard(String userId) {
        return factorModelService.compute(userId).map(fr -> {
            if (fr.isLowConfidence()) return List.<InsightCard>of();
            double sysShare = fr.systematicSharePct();
            double sysVol = fr.systematicVolAnnualized() * 100;
            double idioVol = fr.idiosyncraticVolAnnualized() * 100;
            boolean alert = sysShare > 80;
            List<Computation> numbers = List.of(
                    new Computation(sysShare, "Systematic Risk Share",
                            fr.includedSymbols(), fr.seriesFrom() + " to " + fr.seriesTo(), "%"),
                    new Computation(sysVol, "Systematic Vol (Annualized)",
                            fr.includedSymbols(), null, "%"),
                    new Computation(idioVol, "Idiosyncratic Vol (Annualized)",
                            fr.includedSymbols(), null, "%")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.FACTOR)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Factor Tilt")
                    .headline(String.format("%.4f%% of risk is systematic; %.4f%% idiosyncratic",
                            sysShare, 100 - sysShare))
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── G5: VaR Backtest ───────────────────────────────────────────────────────

    List<InsightCard> varBacktestCard(String userId) {
        List<VarBacktestReport> reports = varBacktestService.backtest(userId);
        if (reports.isEmpty()) return List.of();
        VarBacktestReport rep = reports.get(0);
        double actual = rep.actualBreachRate() * 100;
        double expected = rep.expectedBreachRate() * 100;
        boolean alert = rep.kupiecReject() || rep.clusteringDetected();
        List<Computation> numbers = List.of(
                new Computation(actual, "Actual Breach Rate",
                        List.of(), rep.observations() + "d", "%"),
                new Computation(expected, "Expected Breach Rate",
                        List.of(), rep.observations() + "d", "%"),
                new Computation(rep.kupiecPValue(), "Kupiec POF p-value",
                        List.of(), null, "")
        );
        return List.of(InsightCard.builder()
                .category(InsightCard.Category.BACKTEST)
                .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                .title("VaR Backtest (" + rep.method() + " 95%)")
                .headline(rep.verdict())
                .numbers(numbers)
                .build());
    }

    // ── G6: Marginal Add (StockDeepDive) ──────────────────────────────────────

    List<InsightCard> marginalAddCards(String userId) {
        return portfolioRiskService.compute(userId).map(rd -> {
            if (rd.riskContributors() == null || rd.riskContributors().size() < 2)
                return List.<InsightCard>of();
            List<InsightCard> cards = new ArrayList<>();
            int limit = Math.min(2, rd.riskContributors().size());
            for (int i = 0; i < limit; i++) {
                RiskDecomposition.RiskContributor rc = rd.riskContributors().get(i);
                try {
                    StockDeepDive dd = stockIntelligenceService.analyze(rc.symbol(), userId);
                    if (!dd.hasPriceHistory() || dd.portfolioFit() == null) continue;
                    StockDeepDive.PortfolioFit fit = dd.portfolioFit();
                    double pctRisk = rc.percentContributionToRisk() * 100;
                    double margVol = fit.marginalVolImpact() * 100;
                    double corr = fit.correlationToPortfolio();
                    boolean alert = pctRisk > 30 && !Double.isNaN(margVol) && margVol > 0;
                    List<Computation> numbers = List.of(
                            new Computation(pctRisk, "% Risk Contribution",
                                    List.of(rc.symbol()), rd.observationCount() + "d", "%"),
                            new Computation(Double.isNaN(margVol) ? 0 : margVol,
                                    "Marginal Vol Impact (+1% weight)",
                                    List.of(rc.symbol()), null, "%"),
                            new Computation(Double.isNaN(corr) ? 0 : corr,
                                    "Correlation to Portfolio",
                                    List.of(rc.symbol()), null, "")
                    );
                    cards.add(InsightCard.builder()
                            .category(InsightCard.Category.RISK)
                            .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                            .title("Marginal Risk — " + rc.symbol())
                            .headline(String.format("%s: %.4f%% of portfolio risk, corr=%.4f",
                                    rc.symbol(), pctRisk, Double.isNaN(corr) ? 0 : corr))
                            .numbers(numbers)
                            .symbol(rc.symbol())
                            .build());
                } catch (Exception e) {
                    log.warn("[InsightCard.marginalAdd] {} failed: {}", rc.symbol(), e.getMessage());
                }
            }
            return cards;
        }).orElse(List.of());
    }

    // ── G7: Skill Verdict (TWRR) ──────────────────────────────────────────────

    List<InsightCard> skillVerdictCard(String userId) {
        return portfolioPerformanceService.computeTwrr(userId).map(twrr -> {
            double portAnn = twrr.annualizedTwrr() * 100;
            double bmkAnn = twrr.annualizedBenchmarkTwrr() * 100;
            double active = twrr.activeReturnAnnualized() * 100;
            double ir = twrr.informationRatio();
            boolean alert = active < -2.0 || Double.isNaN(active);
            List<Computation> numbers = List.of(
                    new Computation(Double.isNaN(portAnn) ? 0 : portAnn,
                            "TWRR (Annualized)",
                            List.of(), twrr.from() + " to " + twrr.to(), "%"),
                    new Computation(Double.isNaN(bmkAnn) ? 0 : bmkAnn,
                            "Benchmark TWRR (Annualized, Nifty 50)",
                            List.of("Nifty 50"), twrr.from() + " to " + twrr.to(), "%"),
                    new Computation(Double.isNaN(active) ? 0 : active,
                            "Active Return (Annualized)",
                            List.of(), null, "%"),
                    new Computation(Double.isNaN(ir) ? 0 : ir,
                            "Information Ratio",
                            List.of(), null, "x")
            );
            String headline = Double.isNaN(portAnn)
                    ? "Insufficient TWRR history — need ≥5 portfolio snapshots"
                    : String.format("TWRR %.4f%% p.a. vs benchmark %.4f%% p.a. → active %+.4f%%",
                            portAnn, bmkAnn, active);
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.PERFORMANCE)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Manager Skill — TWRR vs Benchmark")
                    .headline(headline)
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── G8: Attribution (Brinson-Fachler) ─────────────────────────────────────

    List<InsightCard> attributionCard(String userId) {
        return attributionService.compute(userId).map(ar -> {
            if (ar.lowConfidence()) return List.<InsightCard>of();
            double portRet = ar.portfolioReturn() * 100;
            double bmkRet = ar.benchmarkReturn() * 100;
            double excess = ar.excessReturn() * 100;
            double alloc = ar.allocationEffect() * 100;
            double sel = ar.selectionEffect() * 100;
            boolean alert = excess < -2.0;
            List<Computation> numbers = List.of(
                    new Computation(portRet, "Portfolio Return (Linked)",
                            List.of(), ar.windowStart() + " to " + ar.windowEnd(), "%"),
                    new Computation(bmkRet, "Benchmark Return (Nifty 50, Linked)",
                            List.of("Nifty 50"), ar.windowStart() + " to " + ar.windowEnd(), "%"),
                    new Computation(excess, "Excess Return",
                            List.of(), ar.buckets() + " monthly buckets", "%"),
                    new Computation(alloc, "Allocation Effect (Cariño)",
                            List.of(), null, "%"),
                    new Computation(sel, "Selection Effect (Cariño)",
                            List.of(), null, "%")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.PERFORMANCE)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("Brinson-Fachler Attribution")
                    .headline(ar.headline())
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── G9: Tax Harvest ────────────────────────────────────────────────────────

    List<InsightCard> taxCard(String userId) {
        TaxHarvestingService.HarvestPlan plan = taxHarvestingService.suggest(userId);
        boolean empty = plan.exemptionHarvest().isEmpty()
                && plan.boundaryWait().isEmpty()
                && plan.lossHarvest().isEmpty();
        if (empty) return List.of();

        double headroom = plan.exemptionHeadroom().doubleValue();
        double lossPool = plan.lossHarvest().stream()
                .mapToDouble(TaxHarvestingService.LossCandidate::unrealizedLoss).sum();
        boolean alert = lossPool > 50_000
                || (!plan.boundaryWait().isEmpty() && plan.boundaryWait().get(0).daysToLongTerm() <= 7);

        List<Computation> numbers = new ArrayList<>();
        numbers.add(new Computation(headroom, "LTCG Exemption Headroom", List.of(), "FY", "₹"));
        numbers.add(new Computation(lossPool, "Harvestable Loss Pool", List.of(), "FY", "₹"));
        if (!plan.boundaryWait().isEmpty()) {
            TaxHarvestingService.BoundaryCandidate b = plan.boundaryWait().get(0);
            numbers.add(new Computation(b.daysToLongTerm(),
                    "Days to Long-Term (" + b.symbol() + ")",
                    List.of(b.symbol()), null, ""));
        }

        return List.of(InsightCard.builder()
                .category(InsightCard.Category.TAX)
                .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                .title("Tax Harvest Opportunities")
                .headline(String.format("₹%,.0f LTCG exemption headroom; ₹%,.0f harvestable losses",
                        headroom, lossPool))
                .numbers(List.copyOf(numbers))
                .build());
    }

    // ── G10: Goal Funding (Monte Carlo) ───────────────────────────────────────

    List<InsightCard> goalFundingCards(String userId) {
        List<FinancialGoal> goals = goalRepository.findActiveGoals(userId);
        if (goals.isEmpty()) return List.of();

        List<InsightCard> cards = new ArrayList<>();
        for (FinancialGoal goal : goals) {
            try {
                double currentSip = goal.getRequiredMonthlyAmount() != null
                        ? goal.getRequiredMonthlyAmount().doubleValue() : 0.0;
                GoalSimulationResult sim = monteCarloGoalService.simulate(goal, currentSip);
                double prob = sim.probabilityOfSuccess() * 100;
                double req75 = Double.isNaN(sim.requiredSip75()) ? 0 : sim.requiredSip75();
                double target = sim.inflationAdjustedTarget();
                boolean alert = prob < 50;

                List<Computation> numbers = List.of(
                        new Computation(prob, "P(Success) at current SIP",
                                List.of(goal.getName()), sim.months() + "m", "%"),
                        new Computation(sim.currentSip(), "Current SIP",
                                List.of(), "monthly", "₹"),
                        new Computation(req75, "Required SIP for 75% confidence",
                                List.of(), "monthly", "₹"),
                        new Computation(target, "Inflation-Adjusted Target",
                                List.of(goal.getName()), sim.months() + "m", "₹")
                );
                cards.add(InsightCard.builder()
                        .category(InsightCard.Category.GOAL)
                        .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                        .title("Goal Funding — " + goal.getName())
                        .headline(sim.headline())
                        .numbers(numbers)
                        .build());
            } catch (Exception e) {
                log.warn("[InsightCard.goalFunding] '{}' failed: {}", goal.getName(), e.getMessage());
            }
        }
        return cards;
    }

    // ── G11: Look-Through ──────────────────────────────────────────────────────

    List<InsightCard> lookThroughCard(String userId) {
        return lookThroughService.compute(userId).map(lt -> {
            double coverage = lt.mfCoverage() * 100;
            double effHhi = lt.effectiveNameHHI() * 100;
            double residue = lt.unmappedResidue() * 100;
            boolean alert = effHhi > 20 || lt.unmappedResidue() > 0.20;
            List<Computation> numbers = List.of(
                    new Computation(coverage, "MF Look-Through Coverage",
                            new ArrayList<>(lt.asOfByScheme().keySet()), null, "%"),
                    new Computation(effHhi, "Effective Name HHI (post look-through)",
                            List.of(), null, "%"),
                    new Computation(residue, "Unmapped MF Residue",
                            List.of(), null, "%")
            );
            return List.of(InsightCard.builder()
                    .category(InsightCard.Category.ALLOCATION)
                    .severity(alert ? InsightCard.Severity.ALERT : InsightCard.Severity.INFO)
                    .title("MF Look-Through")
                    .headline(lt.headline())
                    .numbers(numbers)
                    .build());
        }).orElse(List.of());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private InsightCard withNarration(InsightCard card) {
        String narration = narrationService.narrate(card);
        return InsightCard.builder()
                .category(card.getCategory())
                .severity(card.getSeverity())
                .title(card.getTitle())
                .headline(card.getHeadline())
                .numbers(card.getNumbers())
                .narration(narration)
                .symbol(card.getSymbol())
                .calibratedConfidence(card.getCalibratedConfidence())
                .trackRecord(card.getTrackRecord())
                .build();
    }

    private InsightCard insufficientDataCard(String topic, InsightCard.Category category) {
        return InsightCard.builder()
                .category(category)
                .severity(InsightCard.Severity.INFO)
                .title(topic)
                .headline("Insufficient data — need more price history to compute " + topic.toLowerCase())
                .numbers(List.of())
                .build();
    }

    private List<InsightCard> safe(Callable<List<InsightCard>> fn) {
        try {
            return fn.call();
        } catch (Exception e) {
            log.warn("[InsightCard] generator threw: {}", e.getMessage());
            return List.of();
        }
    }
}
