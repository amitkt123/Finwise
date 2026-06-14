package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.VarBacktestReport;
import org.amit.finwise.cfo.service.analytics.VarBacktestService.VarMethod;
import org.apache.commons.math3.distribution.TDistribution;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage tests for the rolling VaR backtest (Kupiec POF + Christoffersen independence).
 *
 * Each case drives the package-private static {@code backtest(double[], window, p, method)}
 * with a synthetic return series of known tail behaviour, so the test needs no Spring
 * context and no price data — exactly the synthetic-series bar set by the GARCH/CF tests.
 */
class VarBacktestServiceTest {

    private static final int WINDOW = 250;

    @Test
    void gaussianReturnsAgainstParametricVar_kupiecFailsToReject() {
        // True N(0,σ): parametric VaR is correctly specified → breach rate ≈ p, coverage holds.
        Random rnd = new Random(20260615L);
        int n = WINDOW + 2500;
        double[] r = new double[n];
        for (int t = 0; t < n; t++) r[t] = rnd.nextGaussian() * 0.012;

        VarBacktestReport rep = VarBacktestService.backtest(r, WINDOW, 0.05, VarMethod.PARAMETRIC);
        assertNotNull(rep);

        assertEquals(0.05, rep.actualBreachRate(), 0.015,
                "breach rate should land near the 5% nominal level");
        assertFalse(rep.kupiecReject(),
                "correctly-specified Gaussian VaR must not be rejected, p=" + rep.kupiecPValue());
        assertFalse(rep.clusteringDetected(),
                "iid returns must not trigger the independence (clustering) test");
        assertEquals("VaR well-calibrated", rep.verdict());
    }

    @Test
    void fatTailedReturnsAgainstParametricVar_kupiecRejectsAndFlagsUnderstatement() {
        // Student-t(3) has far heavier tails than the Gaussian the parametric VaR assumes,
        // so realized breaches far exceed p → Kupiec rejects, verdict = understates tails.
        TDistribution t = new TDistribution(3);
        t.reseedRandomGenerator(20260615L);
        int n = WINDOW + 2500;
        // Scale a t(3) draw to ≈1.2% daily vol: var(t_3)=3, sd=√3.
        double scale = 0.012 / Math.sqrt(3.0);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = t.sample() * scale;

        VarBacktestReport rep = VarBacktestService.backtest(r, WINDOW, 0.01, VarMethod.PARAMETRIC);
        assertNotNull(rep);

        assertTrue(rep.actualBreachRate() > rep.expectedBreachRate(),
                "fat tails must breach more often than a Gaussian VaR expects");
        assertTrue(rep.kupiecReject(),
                "Kupiec must reject mis-specified thin-tailed VaR, p=" + rep.kupiecPValue());
        assertTrue(rep.verdict().startsWith("VaR understates tails"),
                "verdict should name the understatement, got: " + rep.verdict());
    }

    @Test
    void clusteredVolatility_christoffersenFlagsClustering() {
        // Calm baseline with occasional multi-day storms. The trailing window is calibrated on
        // calm days, so every storm day breaches together → breaches arrive in clusters (n11>0).
        Random rnd = new Random(7L);
        int n = WINDOW + 1300;
        double[] r = new double[n];
        for (int t = 0; t < n; t++) r[t] = rnd.nextGaussian() * 0.008;
        // Insert 5-day storms of large losses every ~250 days after the window opens.
        for (int start = WINDOW + 40; start + 5 < n; start += 250) {
            for (int k = 0; k < 5; k++) r[start + k] = -0.05 - rnd.nextDouble() * 0.01;
        }

        VarBacktestReport rep = VarBacktestService.backtest(r, WINDOW, 0.05, VarMethod.PARAMETRIC);
        assertNotNull(rep);

        assertTrue(rep.breaches() > 0, "storm days must register as breaches");
        assertTrue(rep.clusteringDetected(),
                "consecutive-day storms must trip Christoffersen independence, p="
                        + rep.christoffersenPValue());
        assertTrue(rep.verdict().contains("cluster"),
                "verdict should flag clustering, got: " + rep.verdict());
    }

    @Test
    void reportCarriesMethodLevelAndConditionalCoverage() {
        Random rnd = new Random(99L);
        int n = WINDOW + 600;
        double[] r = new double[n];
        for (int t = 0; t < n; t++) r[t] = rnd.nextGaussian() * 0.01;

        VarBacktestReport rep = VarBacktestService.backtest(r, WINDOW, 0.05, VarMethod.CORNISH_FISHER);
        assertNotNull(rep);

        assertEquals("Cornish-Fisher", rep.method());
        assertEquals(0.05, rep.confidenceLevel(), 1e-12);
        assertEquals(n - WINDOW, rep.observations());
        assertEquals(rep.kupiecLR() + rep.christoffersenLR(), rep.conditionalCoverageLR(), 1e-12,
                "conditional-coverage LR must be the sum of the two component statistics");
    }

    @Test
    void insufficientHistory_returnsNull() {
        double[] r = new double[WINDOW + 1];   // only one out-of-sample day → no transitions
        assertNull(VarBacktestService.backtest(r, WINDOW, 0.05, VarMethod.PARAMETRIC));
    }
}
