package org.amit.finwise.cfo.service.analytics;

import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.stat.StatUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Known-answer and property tests for the Ledoit-Wolf (2003) constant-correlation
 * shrinkage estimator. Golden values were computed with an independent
 * pure-Python implementation of the paper's formulas on the same fixture.
 */
class LedoitWolfShrinkageTest {

    // 3 assets, T=10 — small enough that every intermediate is hand-checkable
    private static final double[] A = {0.010, -0.020, 0.015, 0.005, -0.010, 0.020, -0.005, 0.000, 0.010, -0.015};
    private static final double[] B = {0.008, -0.015, 0.012, 0.000, -0.008, 0.015, -0.004, 0.002, 0.009, -0.012};
    private static final double[] C = {-0.005, 0.010, -0.020, 0.008, 0.012, -0.010, 0.006, -0.003, 0.000, 0.004};

    private static double[][] fixture() {
        double[][] data = new double[10][3];
        for (int t = 0; t < 10; t++) {
            data[t][0] = A[t];
            data[t][1] = B[t];
            data[t][2] = C[t];
        }
        return data;
    }

    @Test
    void matchesIndependentlyComputedGoldenValues() {
        LedoitWolfShrinkage.Result r = LedoitWolfShrinkage.shrink(fixture());

        assertEquals(8.993795079420898e-02, r.delta(), 1e-12, "shrinkage intensity δ");

        double[][] s = r.sigma();
        assertEquals(1.766666666666667e-04, s[0][0], 1e-14);
        assertEquals(1.211979485601264e-04, s[0][1], 1e-14);
        assertEquals(-9.561064243579446e-05, s[0][2], 1e-14);
        assertEquals(1.069000000000000e-04, s[1][1], 1e-14);
        assertEquals(-7.823576783437818e-05, s[1][2], 1e-14);
        assertEquals(9.928888888888889e-05, s[2][2], 1e-14);
        assertEquals(s[0][1], s[1][0], 0.0, "matrix must be symmetric");
    }

    @Test
    void diagonalIsPreservedAtUnbiasedSampleVariance() {
        // The constant-correlation target keeps f_ii = s_ii, so the shrunk
        // diagonal must equal the N-1 sample variance for ANY delta.
        LedoitWolfShrinkage.Result r = LedoitWolfShrinkage.shrink(fixture());
        assertEquals(StatUtils.variance(A), r.sigma()[0][0], 1e-18);
        assertEquals(StatUtils.variance(B), r.sigma()[1][1], 1e-18);
        assertEquals(StatUtils.variance(C), r.sigma()[2][2], 1e-18);
    }

    @Test
    void intensityStaysInUnitIntervalAndVanishesForLargeSamples() {
        // Heterogeneous true correlations (ρ12 ≈ 0.5, ρ13 = ρ23 = 0): the
        // constant-correlation target is genuinely misspecified, so with T=5000
        // the sample must dominate and δ → 0. (For i.i.d. UNcorrelated data the
        // target is exactly right — γ̂ → 0 and δ → 1 — so that is no test of
        // sample dominance.)
        Random rng = new Random(42);
        double[][] big = new double[5000][3];
        for (int t = 0; t < 5000; t++) {
            double common = rng.nextGaussian() * 0.01;
            big[t][0] = common + rng.nextGaussian() * 0.01;
            big[t][1] = common + rng.nextGaussian() * 0.01;
            big[t][2] = rng.nextGaussian() * 0.01;
        }
        LedoitWolfShrinkage.Result r = LedoitWolfShrinkage.shrink(big);
        assertTrue(r.delta() >= 0.0 && r.delta() <= 1.0, "δ must be clamped to [0,1]");
        assertTrue(r.delta() < 0.05,
                "with T=5000 observations the sample dominates, got δ=" + r.delta());

        LedoitWolfShrinkage.Result small = LedoitWolfShrinkage.shrink(fixture());
        assertTrue(small.delta() >= 0.0 && small.delta() <= 1.0);
        assertTrue(small.delta() > r.delta(), "T=10 must shrink harder than T=5000");
    }

    @Test
    void shrunkCovarianceFromEngine_isPositiveSemiDefiniteEvenWhenCollinear() {
        // Two perfectly collinear assets make the sample matrix singular —
        // the engine's PSD guard must keep all eigenvalues ≥ 0.
        int n = 70;
        LocalDate start = LocalDate.of(2025, 1, 1);
        Map<String, NavigableMap<LocalDate, Double>> series = new LinkedHashMap<>();
        NavigableMap<LocalDate, Double> x = new TreeMap<>();
        NavigableMap<LocalDate, Double> y = new TreeMap<>();
        NavigableMap<LocalDate, Double> z = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            LocalDate d = start.plusDays(i);
            double r = ((i % 7) - 3) * 0.005;
            x.put(d, r);
            y.put(d, r);                       // identical to x
            z.put(d, ((i % 5) - 2) * 0.004);
        }
        series.put("X", x);
        series.put("Y", y);
        series.put("Z", z);

        CovarianceEngine engine = new CovarianceEngine();
        CovarianceEngine.AlignedSeries aligned = engine.align(series, 60);
        CovarianceEngine.ShrinkageResult sr = engine.shrunkCovariance(aligned);

        EigenDecomposition eig = new EigenDecomposition(new Array2DRowRealMatrix(sr.sigma()));
        for (double ev : eig.getRealEigenvalues()) {
            assertTrue(ev >= -1e-12, "eigenvalue must be non-negative, got " + ev);
        }
        assertTrue(sr.delta() >= 0.0 && sr.delta() <= 1.0);
    }

    @Test
    void rejectsDegenerateInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> LedoitWolfShrinkage.shrink(new double[][]{{0.01, 0.02}}));
        assertThrows(IllegalArgumentException.class,
                () -> LedoitWolfShrinkage.shrink(new double[][]{{0.01}, {0.02}}));
    }
}
