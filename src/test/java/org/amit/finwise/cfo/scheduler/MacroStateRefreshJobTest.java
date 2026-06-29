package org.amit.finwise.cfo.scheduler;

import org.amit.finwise.cfo.service.macro.FiiDiiFlowProvider;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.cfo.service.macro.RegimeModelService;
import org.amit.finwise.cfo.service.macro.YieldCurveService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MacroStateRefreshJobTest {

    @Test
    void refreshWritesRegimeAndYieldCurveToMacroState() {
        var macroState = mock(QuantitativeMacroState.class);
        var regimeSvc = mock(RegimeModelService.class);
        var yieldSvc = mock(YieldCurveService.class);
        var fiiProvider = mock(FiiDiiFlowProvider.class);

        // RegimeResult(crisisProbability, filteredCrisisProbability, calmDailyVol, crisisDailyVol,
        //              expectedCalmDurationDays, expectedCrisisDurationDays, smoothedCrisisProb[],
        //              observations, iterations, converged)
        var regimeResult = new RegimeModelService.RegimeResult(
                0.72, 0.65, 0.12, 0.28, 20.0, 10.0, new double[]{0.72}, 60, 10, true);
        when(regimeSvc.fit(any())).thenReturn(Optional.of(regimeResult));
        when(yieldSvc.gsec10y()).thenReturn(BigDecimal.valueOf(7.15));
        when(yieldSvc.slope10y1y()).thenReturn(BigDecimal.valueOf(0.85));
        when(fiiProvider.fetchLatestFlow()).thenReturn(
                new FiiDiiFlowProvider.FiiDiiFlow(BigDecimal.valueOf(-500), BigDecimal.valueOf(300),
                        LocalDate.now()));

        var job = new MacroStateRefreshJob(regimeSvc, yieldSvc, fiiProvider, macroState);
        // Use 60+ returns so the length guard passes; regime fit is fully mocked
        double[] dummyReturns = new double[60];
        job.execute(dummyReturns);

        verify(macroState).setCrisisProbability(eq(0.72), eq("REGIME_MODEL"));
        verify(macroState).setRegimeVolCalm(eq(0.12), eq("REGIME_MODEL"));
        verify(macroState).setRegimeVolCrisis(eq(0.28), eq("REGIME_MODEL"));
        // Use same arithmetic as the implementation to avoid IEEE 754 rounding surprises
        verify(macroState).setYieldCurve10y(
                eq(BigDecimal.valueOf(7.15).doubleValue() / 100.0), eq("YIELD_CURVE"));
        verify(macroState).setYieldCurveSlope(
                eq(BigDecimal.valueOf(0.85).doubleValue() / 100.0), eq("YIELD_CURVE"));
        verify(macroState).setFiiFlowScore(eq(-500.0), eq("FII_PROVIDER"));
    }
}
