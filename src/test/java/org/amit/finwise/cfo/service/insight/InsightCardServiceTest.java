package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.RiskDecomposition.RiskContributor;
import org.amit.finwise.cfo.repository.DismissedInsightCardRepository;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.FactorModelService;
import org.amit.finwise.cfo.service.analytics.LiquidityService;
import org.amit.finwise.cfo.service.analytics.LookThroughService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.StressScenarioService;
import org.amit.finwise.cfo.service.analytics.TradingCostService;
import org.amit.finwise.cfo.service.analytics.VarBacktestService;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.MonteCarloGoalService;
import org.amit.finwise.investment.service.TaxHarvestingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Generator catalog (Phase B2): degrades to an empty list when every source is empty (the
 * brief then renders an insufficient-data note rather than a fabricated number), and renders
 * every figure byte-identically to the engine record.
 */
@ExtendWith(MockitoExtension.class)
class InsightCardServiceTest {

    @Mock PortfolioRiskService portfolioRiskService;
    @Mock FactorModelService factorModelService;
    @Mock VarBacktestService varBacktestService;
    @Mock StressScenarioService stressScenarioService;
    @Mock ConfidenceCalibrationService calibrationService;
    @Mock LiquidityService liquidityService;
    @Mock PortfolioPerformanceService performanceService;
    @Mock AttributionService attributionService;
    @Mock TaxHarvestingService taxHarvestingService;
    @Mock MonteCarloGoalService monteCarloGoalService;
    @Mock FinancialGoalRepository goalRepository;
    @Mock LookThroughService lookThroughService;
    @Mock StockIntelligenceService stockIntelligenceService;
    @Mock DismissedInsightCardRepository dismissedRepo;

    private InsightCardService service() {
        // No scored calls by default → cards keep their raw confidence (Phase C no-ops cleanly).
        lenient().when(calibrationService.report()).thenReturn(List.of());
        lenient().when(calibrationService.cohort(any(), any(), any()))
                .thenReturn(new ConfidenceCalibrationService.Cohort(null, null, 0, 0, 0.0));
        lenient().when(stressScenarioService.stress(any())).thenReturn(List.of());
        // The portfolio-wide generators (skill/attribution/tax/goal/look-through) are left
        // unstubbed: Mockito returns empty Optionals, an empty goals list, and a null HarvestPlan,
        // all of which the generators treat as "no data" → no card, never a fabricated number.
        // Liquidity is likewise unstubbed → null spread map (zero impact). Tests that need any of
        // these stub them explicitly.
        lenient().when(dismissedRepo.findByUserId(any())).thenReturn(java.util.Set.of());
        return new InsightCardService(portfolioRiskService, factorModelService,
                varBacktestService, stressScenarioService, calibrationService,
                dismissedRepo, liquidityService, new TradingCostService(), performanceService,
                attributionService, taxHarvestingService, monteCarloGoalService,
                goalRepository, lookThroughService, stockIntelligenceService);
    }

    @Test
    void emptyEngineYieldsNoCards() {
        when(portfolioRiskService.compute("u")).thenReturn(Optional.empty());
        when(portfolioRiskService.forwardRisk("u")).thenReturn(Optional.empty());
        when(factorModelService.compute("u")).thenReturn(Optional.empty());
        when(varBacktestService.backtest("u")).thenReturn(List.of());

        assertTrue(service().generate("u").isEmpty());
    }

    @Test
    void riskBudgetCardRendersPctContributionByteIdenticalAndIsActionable() {
        RiskContributor top = new RiskContributor("HDFCBANK", 0.30, 1.16, 0.0152, 0.0046, 0.42);
        RiskDecomposition rd = riskDecomposition(List.of(top));

        when(portfolioRiskService.compute("u")).thenReturn(Optional.of(rd));
        lenient().when(portfolioRiskService.forwardRisk("u")).thenReturn(Optional.empty());
        lenient().when(factorModelService.compute("u")).thenReturn(Optional.empty());
        lenient().when(varBacktestService.backtest("u")).thenReturn(List.of());

        List<InsightCard> cards = service().generate("u");

        InsightCard riskBudget = cards.stream()
                .filter(c -> c.category() == InsightCard.Category.RISK_BUDGET)
                .findFirst().orElseThrow();

        // %RC clears the 25% action threshold for the top contributor.
        assertEquals(InsightCard.Severity.ACTION, riskBudget.severity());
        assertEquals("trim", riskBudget.actionVerb());
        assertEquals("HDFCBANK", riskBudget.symbol());

        String expectedPct = String.format("%.1f%%", top.percentContributionToRisk() * 100); // "42.0%"
        boolean byteIdentical = riskBudget.computations().stream()
                .anyMatch(c -> expectedPct.equals(c.value()));
        assertTrue(byteIdentical, "%RC value must be byte-identical to String.format of the engine figure");
    }

    @Test
    void lowContributionStaysInfoAndNonActionable() {
        RiskContributor small = new RiskContributor("ITC", 0.05, 0.7, 0.001, 0.0001, 0.08);
        RiskDecomposition rd = riskDecomposition(List.of(small));

        when(portfolioRiskService.compute("u")).thenReturn(Optional.of(rd));
        lenient().when(portfolioRiskService.forwardRisk("u")).thenReturn(Optional.empty());
        lenient().when(factorModelService.compute("u")).thenReturn(Optional.empty());
        lenient().when(varBacktestService.backtest("u")).thenReturn(List.of());

        InsightCard riskBudget = service().generate("u").stream()
                .filter(c -> c.category() == InsightCard.Category.RISK_BUDGET)
                .findFirst().orElseThrow();

        assertEquals(InsightCard.Severity.INFO, riskBudget.severity());
        assertNull(riskBudget.actionVerb());
    }

    @Test
    void costEffectiveTrimReportsNetBenefitAndStaysAction() {
        RiskContributor top = new RiskContributor("HDFCBANK", 0.30, 1.16, 0.0152, 0.0046, 0.42);
        RiskDecomposition rd = riskDecomposition(List.of(top));

        when(portfolioRiskService.compute("u")).thenReturn(Optional.of(rd));
        lenient().when(portfolioRiskService.forwardRisk("u")).thenReturn(Optional.empty());
        lenient().when(factorModelService.compute("u")).thenReturn(Optional.empty());
        lenient().when(varBacktestService.backtest("u")).thenReturn(List.of());

        InsightCard card = service().generate("u").stream()
                .filter(c -> c.category() == InsightCard.Category.RISK_BUDGET)
                .findFirst().orElseThrow();

        // Tight name (no liquidity report → zero impact): the VaR removed dwarfs statutory cost.
        assertEquals(InsightCard.Severity.ACTION, card.severity());
        assertEquals("trim", card.actionVerb());
        assertTrue(card.computations().stream().anyMatch(c -> "Suggested trim".equals(c.label())),
                "the trim must be sized and shown");
        assertTrue(card.computations().stream().anyMatch(c -> c.label().contains("VaR95 reduction")),
                "net-of-cost VaR benefit must be shown");
    }

    @Test
    void wideSpreadSuppressesTrimAndDowngradesToWatch() {
        RiskContributor top = new RiskContributor("ILLIQ", 0.30, 1.16, 0.0152, 0.0046, 0.42);
        RiskDecomposition rd = riskDecomposition(List.of(top));

        when(portfolioRiskService.compute("u")).thenReturn(Optional.of(rd));
        lenient().when(portfolioRiskService.forwardRisk("u")).thenReturn(Optional.empty());
        lenient().when(factorModelService.compute("u")).thenReturn(Optional.empty());
        lenient().when(varBacktestService.backtest("u")).thenReturn(List.of());
        // 2000 bps spread → half-spread impact swamps the small VaR benefit → trim suppressed.
        when(liquidityService.compute(any(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(Optional.of(liquidityReport("ILLIQ", 2_000.0)));

        InsightCard card = service().generate("u").stream()
                .filter(c -> c.category() == InsightCard.Category.RISK_BUDGET)
                .findFirst().orElseThrow();

        assertEquals(InsightCard.Severity.WATCH, card.severity());
        assertNull(card.actionVerb(), "a cost-ineffective trim must not be recommended");
        assertTrue(card.caveats().stream().anyMatch(s -> s.contains("Trim suppressed")),
                "the suppression must be explained, not silent");
    }

    // ── SKILL ─────────────────────────────────────────────────────────────────────

    @Test
    void skillCardRendersActiveReturnByteIdenticalAndFlagsLagging() {
        // active return negative → lagging the benchmark → WATCH.
        var t = new PortfolioPerformanceService.TwrrResult(
                0.08, 0.06, LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1), 24,
                java.math.BigDecimal.valueOf(1_200_000), 0.12, 0.10, -0.04, 0.08, -0.5);

        InsightCard card = service().skillVerdictCard(t).orElseThrow();

        assertEquals(InsightCard.Severity.WATCH, card.severity());
        String expected = String.format("%+.2f%%", t.activeReturnAnnualized() * 100); // "-4.00%"
        assertTrue(card.computations().stream().anyMatch(c -> expected.equals(c.value())),
                "active return must be byte-identical to the engine figure");
    }

    @Test
    void skillCardSkipsWhenTwrrUnavailable() {
        assertTrue(service().skillVerdictCard(null).isEmpty());
    }

    // ── ATTRIBUTION ───────────────────────────────────────────────────────────────

    @Test
    void attributionCardIsInfoAndRendersExcessByteIdentical() {
        var a = new org.amit.finwise.cfo.model.AttributionReport(
                LocalDate.of(2026, 3, 31), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1), 12,
                0.18, 0.14, 0.04, 0.015, 0.022, 0.003, 0.0,
                List.of(), false, List.of(), null);

        InsightCard card = service().attributionCard(a).orElseThrow();

        assertEquals(InsightCard.Severity.INFO, card.severity());
        String excess = String.format("%+.2f%%", a.excessReturn() * 100); // "+4.00%"
        assertTrue(card.computations().stream().anyMatch(c -> excess.equals(c.value())));
    }

    // ── TAX ─────────────────────────────────────────────────────────────────────

    @Test
    void taxCardIsActionWhenExemptionHeadroomCanBeHarvested() {
        var harvest = new TaxHarvestingService.HarvestCandidate(
                "INFY", LocalDate.of(2023, 1, 1), 10, 50_000, 5_000);
        var plan = new TaxHarvestingService.HarvestPlan(
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                java.math.BigDecimal.valueOf(75_000), List.of(harvest),
                List.of(), List.of(), List.of());

        InsightCard card = service().taxHarvestCard(plan).orElseThrow();

        assertEquals(InsightCard.Severity.ACTION, card.severity());
        assertTrue(card.computations().stream()
                .anyMatch(c -> "₹75,000".equals(c.value())), "headroom rendered in Java");
    }

    @Test
    void taxCardSkipsWhenNoOpportunity() {
        var plan = new TaxHarvestingService.HarvestPlan(
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                java.math.BigDecimal.ZERO, List.of(), List.of(), List.of(), List.of());
        assertTrue(service().taxHarvestCard(plan).isEmpty());
        assertTrue(service().taxHarvestCard(null).isEmpty());
    }

    // ── GOAL ────────────────────────────────────────────────────────────────────

    @Test
    void goalCardIsAlertWhenSuccessProbabilityIsLow() {
        var goal = new org.amit.finwise.goal.model.FinancialGoal();
        goal.setId(7L);
        goal.setName("Retirement");
        var result = new org.amit.finwise.goal.model.GoalSimulationResult(
                "GBM", 10_000, 240, 0.10, 0.15, false, 25_000, 50_000_000,
                0.35, 30_000_000, 38_000_000, 45_000_000, 53_000_000, 62_000_000,
                40_000, 55_000, 70_000, null, List.of());
        when(goalRepository.findActiveGoals("u")).thenReturn(List.of(goal));
        when(monteCarloGoalService.simulate(goal, null)).thenReturn(result);

        List<InsightCard> cards = service().goalFundingCards("u");

        assertEquals(1, cards.size());
        assertEquals(InsightCard.Severity.ALERT, cards.getFirst().severity());
        assertTrue(cards.getFirst().computations().stream().anyMatch(c -> "35%".equals(c.value())),
                "probability of success rendered byte-identically");
    }

    @Test
    void goalCardsEmptyWhenNoActiveGoals() {
        when(goalRepository.findActiveGoals("u")).thenReturn(List.of());
        assertTrue(service().goalFundingCards("u").isEmpty());
    }

    // ── LOOKTHROUGH ───────────────────────────────────────────────────────────────

    @Test
    void lookThroughCardWatchesWhenFundOverlapRaisesEffectiveHHI() {
        var lt = new org.amit.finwise.cfo.model.LookThroughResult(
                Map.of("INF200K01VT8", LocalDate.of(2026, 5, 31)),
                Map.of(), Map.of(), Map.of(), Map.of(),
                0.40, 0.05, 0.875,
                0.12, 0.18,   // name HHI jumps 0.12 → 0.18 (>15%)
                0.20, 0.24,
                true, List.of(), null);

        InsightCard card = service().lookThroughCard(lt).orElseThrow();

        assertEquals(InsightCard.Severity.WATCH, card.severity());
        assertTrue(card.computations().stream().anyMatch(c -> "0.120 → 0.180".equals(c.value())));
    }

    @Test
    void lookThroughCardSkipsWhenNoMutualFunds() {
        var lt = new org.amit.finwise.cfo.model.LookThroughResult(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                0.0, 0.0, 0.0, 0.10, 0.10, 0.10, 0.10, false, List.of(), null);
        assertTrue(service().lookThroughCard(lt).isEmpty());
    }

    // ── MARGINAL_ADD ──────────────────────────────────────────────────────────────

    @Test
    void marginalAddCardIsActionToAddOnInitiateVerdict() {
        var fit = new org.amit.finwise.cfo.model.StockDeepDive.PortfolioFit(
                false, 0.0, 0.95, Double.NaN, "IT", "low overlap",
                0.30, -0.004, 0.172, 0.05, "INITIATE", "diversifies the book");

        InsightCard card = service().marginalAddCard("INFY", fit).orElseThrow();

        assertEquals(InsightCard.Severity.ACTION, card.severity());
        assertEquals("add", card.actionVerb());
        assertEquals("INFY", card.symbol());
    }

    @Test
    void marginalAddCardSkipsWhenFitUncomputable() {
        var fit = new org.amit.finwise.cfo.model.StockDeepDive.PortfolioFit(
                false, 0.0, Double.NaN, Double.NaN, "IT", "—",
                Double.NaN, Double.NaN, Double.NaN, 0.05, "NEEDS-MORE-DATA", "insufficient history");
        assertTrue(service().marginalAddCard("INFY", fit).isEmpty());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static org.amit.finwise.cfo.model.LiquidityReport liquidityReport(String symbol, double spreadBps) {
        return new org.amit.finwise.cfo.model.LiquidityReport(
                List.of(symbol), List.of(), List.of(),
                Map.of(symbol, spreadBps), Map.of(symbol, 1.0),
                1.0, 1.0, 0.0, 0.0, 0.0, List.of(), "test");
    }


    private static RiskDecomposition riskDecomposition(List<RiskContributor> contributors) {
        return new RiskDecomposition(
                List.of("HDFCBANK"), List.of(), 0.0, List.of(), 740,
                LocalDate.of(2023, 6, 1), LocalDate.of(2026, 6, 13), false,
                0.18, 0.011, 0.25,
                1.05, Map.of("HDFCBANK", 1.16),
                40000, 56000, 48124, 67000, 45000, 60000,
                -0.3, 1.2,
                contributors,
                1.4, 6.2, 0.12, 0.22,
                0.9, 1.1, 0.04,
                -0.25, 0.8,
                "Test headline");
    }
}
