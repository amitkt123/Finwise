package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.RiskDecomposition.RiskContributor;
import org.amit.finwise.cfo.service.analytics.FactorModelService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.VarBacktestService;
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
    @Mock ConfidenceCalibrationService calibrationService;

    private InsightCardService service() {
        // No scored calls by default → cards keep their raw confidence (Phase C no-ops cleanly).
        lenient().when(calibrationService.report()).thenReturn(List.of());
        lenient().when(calibrationService.cohort(any(), any(), any()))
                .thenReturn(new ConfidenceCalibrationService.Cohort(null, null, 0, 0, 0.0));
        return new InsightCardService(portfolioRiskService, factorModelService,
                varBacktestService, calibrationService);
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

    // ── fixtures ────────────────────────────────────────────────────────────────

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
