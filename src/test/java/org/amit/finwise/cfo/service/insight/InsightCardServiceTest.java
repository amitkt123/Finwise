package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.*;
import org.amit.finwise.cfo.service.analytics.*;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.MonteCarloGoalService;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.service.TaxHarvestingService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InsightCardService generators.
 * All collaborators are mocked.  narrationService returns the headline as-is
 * so number assertions are not polluted by LLM text.
 */
class InsightCardServiceTest {

    // ── Mocks ──────────────────────────────────────────────────────────────────

    private final PortfolioRiskService riskSvc = mock(PortfolioRiskService.class);
    private final VarBacktestService backtestSvc = mock(VarBacktestService.class);
    private final FactorModelService factorSvc = mock(FactorModelService.class);
    private final PortfolioPerformanceService perfSvc = mock(PortfolioPerformanceService.class);
    private final MoneyWeightedReturnService mwrSvc = mock(MoneyWeightedReturnService.class);
    private final AttributionService attrSvc = mock(AttributionService.class);
    private final LookThroughService ltSvc = mock(LookThroughService.class);
    private final TaxHarvestingService taxSvc = mock(TaxHarvestingService.class);
    private final MonteCarloGoalService mcSvc = mock(MonteCarloGoalService.class);
    private final StockIntelligenceService stockSvc = mock(StockIntelligenceService.class);
    private final FinancialGoalRepository goalRepo = mock(FinancialGoalRepository.class);
    private final InvestmentRepository investRepo = mock(InvestmentRepository.class);

    // Narration returns the headline unchanged — keeps number assertions clean.
    private final InsightNarrationService narration = mock(InsightNarrationService.class);

    private final InsightCardService svc = new InsightCardService(
            riskSvc, backtestSvc, factorSvc, perfSvc, mwrSvc,
            attrSvc, ltSvc, taxSvc, mcSvc, stockSvc,
            goalRepo, investRepo, narration);

    // ── Stub helpers ────────────────────────────────────────────────────────────

    private static final String USER = "u1";

    {
        // Narration stub: echo the headline
        when(narration.narrate(any())).thenAnswer(inv ->
                ((InsightCard) inv.getArgument(0)).getHeadline());
    }

    // ── G1: empty source → insufficient-data card ─────────────────────────────

    @Test
    void riskBudgetEmptySourceReturnsInsufficientData() {
        when(riskSvc.compute(USER)).thenReturn(Optional.empty());
        List<InsightCard> cards = svc.riskBudgetCards(USER);
        assertEquals(1, cards.size());
        assertTrue(cards.get(0).getHeadline().toLowerCase().contains("insufficient"),
                "Expected insufficient-data headline");
    }

    // ── G1: % risk contribution passes through byte-identical ─────────────────

    @Test
    void riskBudgetPctRCByteIdentical() {
        RiskDecomposition.RiskContributor top =
                new RiskDecomposition.RiskContributor("HDFCBANK", 0.22, 1.16, 0.001, 0.0008, 0.32);

        RiskDecomposition rd = buildRd(top);
        when(riskSvc.compute(USER)).thenReturn(Optional.of(rd));

        List<InsightCard> cards = svc.riskBudgetCards(USER);
        assertEquals(1, cards.size());

        // 0.32 × 100 = 32.0 should appear as the first number's value
        Computation pctRcComp = cards.get(0).getNumbers().get(0);
        assertEquals(32.0, pctRcComp.value(), 1e-9,
                "% risk contribution should be 0.32 * 100 = 32.0");
    }

    // ── G1: ALERT when top contributor > 30% ──────────────────────────────────

    @Test
    void riskBudgetAlertAbove30Pct() {
        RiskDecomposition.RiskContributor top =
                new RiskDecomposition.RiskContributor("RELIANCE", 0.30, 1.0, 0.001, 0.0009, 0.35);
        when(riskSvc.compute(USER)).thenReturn(Optional.of(buildRd(top)));

        List<InsightCard> cards = svc.riskBudgetCards(USER);
        assertEquals(InsightCard.Severity.ALERT, cards.get(0).getSeverity());
    }

    // ── G5: VaR backtest — empty list → no card ───────────────────────────────

    @Test
    void varBacktestEmptyReturnsEmpty() {
        when(backtestSvc.backtest(USER)).thenReturn(List.of());
        assertTrue(svc.varBacktestCard(USER).isEmpty());
    }

    // ── G10: goal funding cards — no goals → empty ────────────────────────────

    @Test
    void goalFundingNoGoalsReturnsEmpty() {
        when(goalRepo.findActiveGoals(USER)).thenReturn(List.of());
        assertTrue(svc.goalFundingCards(USER).isEmpty());
    }

    // ── generate(): ALERT cards sorted before INFO ─────────────────────────────

    @Test
    void generateSortsAlertBeforeInfo() {
        // riskBudget: >30% → ALERT; everything else empty
        RiskDecomposition.RiskContributor top =
                new RiskDecomposition.RiskContributor("X", 0.4, 1.0, 0.001, 0.0009, 0.40);
        when(riskSvc.compute(USER)).thenReturn(Optional.of(buildRd(top)));
        when(riskSvc.forwardRisk(USER)).thenReturn(Optional.empty());
        when(backtestSvc.backtest(USER)).thenReturn(List.of());
        when(factorSvc.compute(USER)).thenReturn(Optional.empty());
        when(perfSvc.computeTwrr(USER)).thenReturn(Optional.empty());
        when(attrSvc.compute(USER)).thenReturn(Optional.empty());
        when(ltSvc.compute(USER)).thenReturn(Optional.empty());
        when(taxSvc.suggest(USER)).thenReturn(emptyPlan());
        when(goalRepo.findActiveGoals(USER)).thenReturn(List.of());

        List<InsightCard> cards = svc.generate(USER);
        assertFalse(cards.isEmpty());
        // First card (if ALERT) must not be followed by an ALERT after an INFO
        for (int i = 1; i < cards.size(); i++) {
            if (cards.get(i).getSeverity() == InsightCard.Severity.ALERT) {
                assertEquals(InsightCard.Severity.ALERT, cards.get(i - 1).getSeverity(),
                        "ALERT card at index " + i + " follows an INFO card — sort broken");
            }
        }
    }

    // ── Stub builders ──────────────────────────────────────────────────────────

    private static RiskDecomposition buildRd(RiskDecomposition.RiskContributor top) {
        return new RiskDecomposition(
                List.of(top.symbol()), List.of(), 0.0,
                List.of(), 252, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1), false,
                0.18, 0.018 / Math.sqrt(252), 0.1,
                1.1, java.util.Map.of(top.symbol(), 1.1),
                50000, 80000, 48000, 76000, 47000, 55000,
                -0.05, 1.5,
                List.of(top),
                1.3, 5.0, 0.15, 0.12,
                0.9, 1.1, 0.05,
                -0.12, 2.5,
                top.symbol() + " is the top risk contributor"
        );
    }

    private static TaxHarvestingService.HarvestPlan emptyPlan() {
        return new TaxHarvestingService.HarvestPlan(
                LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31),
                BigDecimal.valueOf(125000),
                List.of(), List.of(), List.of(), List.of());
    }
}
