package org.amit.finwise.cfo.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.macro.FiiDiiFlowProvider;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.cfo.service.macro.RegimeModelService;
import org.amit.finwise.cfo.service.macro.YieldCurveService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Encapsulates the logic for the daily macro-state refresh (16:15 IST).
 *
 * Separated from CFOScheduler so the regime / yield-curve / FII logic can be
 * unit-tested without a full Spring context or database. CFOScheduler fetches
 * the NIFTY price history and calls {@link #execute(double[])} with the
 * pre-computed daily returns.
 *
 * Source tags (used in QuantitativeMacroState audit log):
 *   - "REGIME_MODEL" — 2-state HMM via RegimeModelService
 *   - "YIELD_CURVE"  — configured G-sec tenors via YieldCurveService
 *   - "FII_PROVIDER" — NSE FII/DII flow via FiiDiiFlowProvider
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MacroStateRefreshJob {

    private final RegimeModelService regimeModelService;
    private final YieldCurveService yieldCurveService;
    private final FiiDiiFlowProvider fiiDiiFlowProvider;
    private final QuantitativeMacroState quantitativeMacroState;

    /**
     * Run all three sub-refreshes against the supplied NIFTY daily return series.
     * Each sub-call is independently guarded so one failure does not abort the others.
     *
     * @param niftyReturns daily log or simple returns for NIFTY 50, chronological.
     *                     At least 60 observations required for the regime model.
     */
    public void execute(double[] niftyReturns) {
        refreshRegime(niftyReturns);
        refreshYieldCurve();
        refreshFiiFlow();
    }

    // ── Regime model (2-state HMM) ────────────────────────────────────────────

    private void refreshRegime(double[] niftyReturns) {
        try {
            if (niftyReturns == null || niftyReturns.length < 60) {
                log.warn("[MacroRefresh] Insufficient Nifty history ({} obs) — regime model skipped",
                        niftyReturns == null ? 0 : niftyReturns.length);
                return;
            }
            regimeModelService.fit(niftyReturns).ifPresent(r -> {
                quantitativeMacroState.setCrisisProbability(r.crisisProbability(), "REGIME_MODEL");
                quantitativeMacroState.setRegimeVolCalm(r.calmDailyVol(), "REGIME_MODEL");
                quantitativeMacroState.setRegimeVolCrisis(r.crisisDailyVol(), "REGIME_MODEL");
                log.info("[MacroRefresh] Regime updated: crisisP={}, calmVol={}, crisisVol={}",
                        String.format("%.3f", r.crisisProbability()),
                        String.format("%.4f", r.calmDailyVol()),
                        String.format("%.4f", r.crisisDailyVol()));
            });
        } catch (Exception e) {
            log.warn("[MacroRefresh] Regime fit failed: {}", e.getMessage());
        }
    }

    // ── Yield curve (config-driven G-sec tenors) ──────────────────────────────

    private void refreshYieldCurve() {
        try {
            BigDecimal y10 = yieldCurveService.gsec10y();
            BigDecimal slope = yieldCurveService.slope10y1y();
            if (y10 != null) {
                quantitativeMacroState.setYieldCurve10y(y10.doubleValue() / 100.0, "YIELD_CURVE");
                log.info("[MacroRefresh] Yield 10Y updated: {}%", y10);
            }
            if (slope != null) {
                quantitativeMacroState.setYieldCurveSlope(slope.doubleValue() / 100.0, "YIELD_CURVE");
                log.info("[MacroRefresh] Yield slope (10Y-1Y) updated: {}pp", slope);
            }
        } catch (Exception e) {
            log.warn("[MacroRefresh] Yield curve refresh failed: {}", e.getMessage());
        }
    }

    // ── FII/DII institutional flow ────────────────────────────────────────────

    private void refreshFiiFlow() {
        try {
            FiiDiiFlowProvider.FiiDiiFlow flow = fiiDiiFlowProvider.fetchLatestFlow();
            if (flow != null) {
                double raw = flow.fiiNetFlowCr().doubleValue();
                quantitativeMacroState.setFiiFlowScore(raw, "FII_PROVIDER");
                log.info("[MacroRefresh] FII flow updated: {} Cr ({})", raw, flow.asOf());
            }
        } catch (Exception e) {
            log.warn("[MacroRefresh] FII flow refresh failed: {}", e.getMessage());
        }
    }
}
