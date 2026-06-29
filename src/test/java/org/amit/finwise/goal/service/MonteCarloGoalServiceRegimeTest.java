package org.amit.finwise.goal.service;

import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.goal.config.GoalMcProperties;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MonteCarloGoalServiceRegimeTest {

    private GoalMcProperties props() {
        GoalMcProperties p = new GoalMcProperties();
        p.setPaths(1000);
        p.setSeed(42L);
        return p;
    }

    private FinancialGoal minimalGoal() {
        return FinancialGoal.builder()
                .userId("test-user")
                .name("Retirement")
                .type(FinancialGoal.GoalType.RETIREMENT)
                .targetAmount(BigDecimal.valueOf(10_000_000))
                .currentAmount(BigDecimal.valueOf(100_000))
                .targetDate(LocalDate.now().plusYears(10))
                .startDate(LocalDate.now())
                .build();
    }

    /**
     * When p_crisis=1.0, sigmaCalm=0.12, sigmaCrisis=0.30, the regime blend must produce
     * effectiveSigma=0.30 and regimeAdjusted=true in the returned GoalSimulationResult.
     */
    @Test
    void fullCrisisBlendUsesRegimeVol() {
        QuantitativeMacroState macroState = mock(QuantitativeMacroState.class);
        when(macroState.getCrisisProbability()).thenReturn(1.0);
        when(macroState.getRegimeVolCalm()).thenReturn(0.12);
        when(macroState.getRegimeVolCrisis()).thenReturn(0.30);
        when(macroState.getYieldCurve10y()).thenReturn(Double.NaN); // skip yield-floor branch

        PortfolioRiskService portfolioRiskService = mock(PortfolioRiskService.class);
        // Return empty so simulate() falls back to defaultVol — lets the regime blend take effect
        when(portfolioRiskService.estimateDriftVol(any())).thenReturn(Optional.empty());

        MonteCarloGoalService service = new MonteCarloGoalService(portfolioRiskService, props(), macroState);

        GoalSimulationResult result = service.simulate(minimalGoal(), 10_000.0);

        assertThat(result.regimeAdjusted()).isTrue();
        assertThat(result.effectiveSigma()).isEqualTo(0.30);
    }

    /**
     * When p_crisis=0.0 and both regime vols are NaN, the service must NOT apply the regime
     * blend: regimeAdjusted stays false and effectiveSigma equals the historical vol (0.18)
     * returned by PortfolioRiskService.
     */
    @Test
    void zeroCrisisKeepsHistoricalVol() {
        QuantitativeMacroState macroState = mock(QuantitativeMacroState.class);
        when(macroState.getCrisisProbability()).thenReturn(0.0);
        when(macroState.getRegimeVolCalm()).thenReturn(Double.NaN);
        when(macroState.getRegimeVolCrisis()).thenReturn(Double.NaN);
        when(macroState.getYieldCurve10y()).thenReturn(Double.NaN); // skip yield-floor branch

        PortfolioRiskService portfolioRiskService = mock(PortfolioRiskService.class);
        // Provide historical vol = 0.18 so effectiveSigma is determined by portfolio history
        when(portfolioRiskService.estimateDriftVol(any())).thenReturn(
                Optional.of(new PortfolioRiskService.DriftVol(0.10, 0.18, 36)));

        MonteCarloGoalService service = new MonteCarloGoalService(portfolioRiskService, props(), macroState);

        GoalSimulationResult result = service.simulate(minimalGoal(), 5_000.0);

        assertThat(result.regimeAdjusted()).isFalse();
        assertThat(result.effectiveSigma()).isEqualTo(0.18);
    }
}
