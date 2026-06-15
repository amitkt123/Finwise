package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.service.analytics.StressScenarioService.StressResult;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Stress scenarios (Phase E). Loads the real classpath CSV so the acceptance case is exercised
 * end-to-end: a single-factor (MKT-only) portfolio must reproduce {@code β·shock·V} exactly,
 * and the factor-model estimate must pick up a sector tilt on a multi-factor book.
 */
@ExtendWith(MockitoExtension.class)
class StressScenarioServiceTest {

    @Mock FactorModelService factorModelService;
    @Mock InvestmentRepository investmentRepository;

    private static final double V = 1_000_000.0;

    private StressScenarioService service() {
        StressScenarioService s = new StressScenarioService(factorModelService, investmentRepository);
        s.loadScenarios(new ClassPathResource("data/stress_scenarios.csv"));
        return s;
    }

    @Test
    void singleFactorPortfolioReproducesBetaShockValueExactly() {
        double betaMkt = 1.20;
        FactorRiskReport fr = report(List.of("RELIANCE"), Map.of("MKT", betaMkt), false);
        when(factorModelService.compute("u")).thenReturn(Optional.of(fr));
        stubValue("RELIANCE", V);

        List<StressResult> results = service().stress("u");
        assertFalse(results.isEmpty());

        for (StressResult r : results) {
            // Only MKT loads → factor-model P&L IS the beta-only cross-check, exactly.
            double expected = betaMkt * (r.niftyShockPct() / 100.0) * V;
            assertEquals(expected, r.factorModelPnl(), 1e-6, r.scenario());
            assertEquals(r.betaOnlyPnl(), r.factorModelPnl(), 1e-9, r.scenario());
            assertEquals(V, r.portfolioValue(), 1e-9);
        }
    }

    @Test
    void worstCaseIsCovidAndSortedWorstFirst() {
        FactorRiskReport fr = report(List.of("RELIANCE"), Map.of("MKT", 1.0), false);
        when(factorModelService.compute("u")).thenReturn(Optional.of(fr));
        stubValue("RELIANCE", V);

        List<StressResult> results = service().stress("u");

        // COVID is the deepest Nifty shock (−13%), so with positive beta it's the worst loss, first.
        assertEquals("COVID_2020_03_23", results.getFirst().scenario());
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).factorModelPnl() <= results.get(i).factorModelPnl(),
                    "results must be ordered worst-loss first");
        }
    }

    @Test
    void sectorTiltMakesFactorModelDivergeFromBetaOnly() {
        // Bank-heavy book: MKT beta 1.0 plus a positive loading on the bank spread factor.
        // The bank spread is negative in COVID, so the factor model shows a DEEPER loss than
        // the market-beta-only cross-check.
        FactorRiskReport fr = report(List.of("HDFCBANK"),
                Map.of("MKT", 1.0, "^NSEBANK", 0.8), false);
        when(factorModelService.compute("u")).thenReturn(Optional.of(fr));
        stubValue("HDFCBANK", V);

        StressResult covid = service().stress("u").stream()
                .filter(r -> r.scenario().equals("COVID_2020_03_23"))
                .findFirst().orElseThrow();

        assertNotEquals(covid.betaOnlyPnl(), covid.factorModelPnl(), 1.0);
        assertTrue(covid.factorModelPnl() < covid.betaOnlyPnl(),
                "negative bank-spread shock on a positive bank tilt deepens the loss");
        assertTrue(covid.appliedFactorShocks().containsKey("^NSEBANK"));
    }

    @Test
    void emptyFactorModelYieldsNoResults() {
        when(factorModelService.compute("u")).thenReturn(Optional.empty());
        assertTrue(service().stress("u").isEmpty());
    }

    @Test
    void nonPositiveValueYieldsNoResults() {
        FactorRiskReport fr = report(List.of("RELIANCE"), Map.of("MKT", 1.0), false);
        when(factorModelService.compute("u")).thenReturn(Optional.of(fr));
        // No matching active investments → included value is 0.
        when(investmentRepository.findActiveInvestments("u")).thenReturn(List.of());

        assertTrue(service().stress("u").isEmpty());
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private void stubValue(String symbol, double value) {
        Investment inv = Investment.builder()
                .symbol(symbol)
                .currentValue(BigDecimal.valueOf(value))
                .build();
        lenient().when(investmentRepository.findActiveInvestments("u")).thenReturn(List.of(inv));
    }

    private static FactorRiskReport report(List<String> included, Map<String, Double> betas,
                                           boolean lowConfidence) {
        return new FactorRiskReport(
                included, List.of(), 0.0, List.of(), lowConfidence,
                LocalDate.of(2024, 1, 1), LocalDate.of(2026, 6, 13),
                List.copyOf(betas.keySet()), betas,
                0.18, 0.10, 0.20, 80.0, Map.of(),
                0.21, List.of(), "test");
    }
}
