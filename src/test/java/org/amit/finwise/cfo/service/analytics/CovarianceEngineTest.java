package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known-answer tests for the covariance / beta / marginal-impact math.
 *
 * Every expected value here is hand-computed from a small synthetic return matrix
 * (Apache Commons Math uses the unbiased N-1 sample covariance).
 */
class CovarianceEngineTest {

    private final CovarianceEngine engine = new CovarianceEngine();

    private static NavigableMap<LocalDate, Double> series(LocalDate[] dates, double... values) {
        NavigableMap<LocalDate, Double> m = new TreeMap<>();
        for (int i = 0; i < dates.length; i++) m.put(dates[i], values[i]);
        return m;
    }

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < n; i++) d[i] = start.plusDays(i);
        return d;
    }

    @Test
    void covarianceMatrix_matchesHandComputedValues() {
        // Three assets over 4 aligned days.
        //   A = [0.01, 0.02, 0.03, 0.04]  (mean 0.025)
        //   B = [0.04, 0.03, 0.02, 0.01]  (perfect negative of A)
        //   C = [0.01, 0.02, 0.03, 0.04]  (identical to A)
        // Hand-computed (N-1 = 3):
        //   Var(A)    =  0.0005 / 3 =  1.666666e-4
        //   Cov(A,B)  = -0.0005 / 3 = -1.666666e-4   (ρ = -1)
        //   Cov(A,C)  =  Var(A)                       (ρ = +1)
        LocalDate[] d = dates(4);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("A", series(d, 0.01, 0.02, 0.03, 0.04));
        returns.put("B", series(d, 0.04, 0.03, 0.02, 0.01));
        returns.put("C", series(d, 0.01, 0.02, 0.03, 0.04));

        CovarianceEngine.AlignedSeries aligned = engine.align(returns, 4);
        assertNotNull(aligned);
        assertEquals(4, aligned.dates().size());

        double[][] cov = engine.covarianceMatrix(aligned);
        double varA = 0.0005 / 3.0;

        assertEquals(varA,  cov[0][0], 1e-12, "Var(A)");
        assertEquals(-varA, cov[0][1], 1e-12, "Cov(A,B) = -Var(A)");
        assertEquals(varA,  cov[0][2], 1e-12, "Cov(A,C) = Var(A)");
        assertEquals(varA,  cov[2][2], 1e-12, "Var(C) = Var(A)");
    }

    @Test
    void align_returnsNull_whenBelowMinObservations() {
        LocalDate[] d = dates(3);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("A", series(d, 0.01, 0.02, 0.03));
        // Require 4 common dates but only 3 exist.
        assertEquals(null, engine.align(returns, 4));
    }

    @Test
    void betaStats_recoversExactBeta_whenStockIsTwiceBenchmark() {
        // 70 days; benchmark cycles through {-0.02,-0.01,0,0.01,0.02}; stock = 2 * benchmark.
        // Then beta = Cov(stock,bench)/Var(bench) = 2 exactly.
        int n = 70;
        LocalDate[] d = dates(n);
        double[] bench = new double[n];
        double[] stock = new double[n];
        for (int i = 0; i < n; i++) {
            bench[i] = ((i % 5) - 2) * 0.01;
            stock[i] = 2.0 * bench[i];
        }
        CovarianceEngine.BetaStats bs =
                engine.betaStats(series(d, stock), series(d, bench));

        assertEquals(70, bs.observations());
        assertEquals(2.0, bs.beta(), 1e-12, "beta should be exactly 2");
    }

    @Test
    void betaStats_returnsNaN_whenInsufficientOverlap() {
        // Only 10 days < MIN_OBSERVATIONS (60).
        int n = 10;
        LocalDate[] d = dates(n);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = 0.001 * i;
        CovarianceEngine.BetaStats bs = engine.betaStats(series(d, v), series(d, v));
        assertTrue(Double.isNaN(bs.beta()));
    }

    @Test
    void marginalVolImpact_addingIdenticalAsset_leavesVolUnchangedAndRhoOne() {
        // Existing portfolio is 100% asset X. Candidate Y is an identical return series.
        // Adding Y at any weight cannot change portfolio vol, and ρ(Y, portfolio) = 1.
        int n = 70;
        LocalDate[] d = dates(n);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = ((i % 7) - 3) * 0.005; // varied, non-constant

        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("X", series(d, r));
        returns.put("Y", series(d, r)); // identical to X

        Map<String, Double> existingWeights = Map.of("X", 1.0);

        CovarianceEngine.MarginalImpact mi =
                engine.marginalVolImpact(returns, existingWeights, "Y", 0.05);

        assertEquals(mi.existingVolAnnualized(), mi.newVolAnnualized(), 1e-9,
                "adding an identical asset must not change vol");
        assertEquals(0.0, mi.deltaVol(), 1e-9, "Δσ = 0");
        assertEquals(1.0, mi.correlationToPortfolio(), 1e-9, "ρ = 1 for an identical series");
    }

    @Test
    void ewmaVolatility_seedsOnFirstTwentyObsThenRecurses() {
        // 23 observations: seed = N-1 variance of the first 20 only (no look-ahead),
        // then three lambda-recursion steps over observations 21..23.
        int n = 23;
        LocalDate[] d = dates(n);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = ((i % 5) - 2) * 0.004; // varied, non-constant
        NavigableMap<LocalDate, Double> returns = series(d, r);

        double[] seed = java.util.Arrays.copyOfRange(r, 0, CovarianceEngine.EWMA_SEED_OBSERVATIONS);
        double variance = org.apache.commons.math3.stat.StatUtils.variance(seed);
        for (int i = CovarianceEngine.EWMA_SEED_OBSERVATIONS; i < n; i++) {
            variance = 0.94 * variance + 0.06 * r[i] * r[i];
        }

        assertEquals(Math.sqrt(variance) * Math.sqrt(252.0),
                engine.ewmaVolatilityAnnualized(returns, 0.94), 1e-12);
    }

    @Test
    void ewmaVolatility_shortSeriesSeedsOnAllObservationsWithoutRecursion() {
        LocalDate[] d = dates(4);
        NavigableMap<LocalDate, Double> returns = series(d, 0.01, -0.02, 0.03, -0.01);

        double[] all = {0.01, -0.02, 0.03, -0.01};
        double variance = org.apache.commons.math3.stat.StatUtils.variance(all);

        assertEquals(Math.sqrt(variance) * Math.sqrt(252.0),
                engine.ewmaVolatilityAnnualized(returns, 0.94), 1e-12);
    }

    @Test
    void maxDrawdown_returnsWorstPeakToTroughLoss() {
        LocalDate[] d = dates(5);
        NavigableMap<LocalDate, Double> prices = series(d, 100, 120, 90, 110, 80);

        assertEquals(-1.0 / 3.0, engine.maxDrawdown(prices), 1e-12);
    }

    @Test
    void downsideBeta_usesOnlyNegativeBenchmarkDays() {
        int n = 80;
        LocalDate[] d = dates(n);
        double[] bench = new double[n];
        double[] stock = new double[n];
        for (int i = 0; i < n; i++) {
            bench[i] = i % 2 == 0 ? -0.01 * (1 + (i % 5)) : 0.01 * (1 + (i % 5));
            stock[i] = 1.5 * bench[i] + (bench[i] < 0 ? -0.002 : 0.002);
        }

        CovarianceEngine.BetaStats bs = engine.downsideBetaStats(series(d, stock), series(d, bench));

        assertEquals(40, bs.observations());
        assertTrue(Double.isNaN(bs.beta()), "40 downside days is below the 60-day confidence floor");

        n = 140;
        d = dates(n);
        bench = new double[n];
        stock = new double[n];
        for (int i = 0; i < n; i++) {
            bench[i] = i % 2 == 0 ? -0.01 * (1 + (i % 5)) : 0.01 * (1 + (i % 5));
            stock[i] = 1.5 * bench[i] + (bench[i] < 0 ? -0.002 : 0.002);
        }

        bs = engine.downsideBetaStats(series(d, stock), series(d, bench));
        assertEquals(70, bs.observations());
        assertEquals(1.5, bs.beta(), 1e-12);
    }

    @Test
    void rollingBeta_returnsWindowEndBetas() {
        int n = 70;
        LocalDate[] d = dates(n);
        double[] bench = new double[n];
        double[] stock = new double[n];
        for (int i = 0; i < n; i++) {
            bench[i] = ((i % 7) - 3) * 0.01;
            stock[i] = 2.0 * bench[i];
        }

        NavigableMap<LocalDate, Double> rolling = engine.rollingBeta(series(d, stock), series(d, bench), 60);

        assertEquals(11, rolling.size());
        assertEquals(2.0, rolling.firstEntry().getValue(), 1e-12);
        assertEquals(2.0, rolling.lastEntry().getValue(), 1e-12);
    }

    @Test
    void stressedCorrelation_usesWorstBenchmarkDays() {
        int n = 100;
        LocalDate[] d = dates(n);
        double[] bench = new double[n];
        double[] stock = new double[n];
        for (int i = 0; i < n; i++) {
            bench[i] = (i - 50) / 1000.0;
            stock[i] = i < 20 ? 2.0 * bench[i] : -bench[i];
        }

        double rho = engine.stressedCorrelation(series(d, stock), series(d, bench), 0.20);

        assertEquals(1.0, rho, 1e-12);
    }

    @Test
    void averageTradedValue_usesOnlyOverlappingPriceAndVolumeDates() {
        LocalDate[] d = dates(3);
        NavigableMap<LocalDate, Double> prices = series(d, 100, 110, 120);
        NavigableMap<LocalDate, Long> volumes = new TreeMap<>();
        volumes.put(d[0], 10L);
        volumes.put(d[2], 20L);

        assertEquals((100 * 10 + 120 * 20) / 2.0,
                engine.averageTradedValue(prices, volumes), 1e-12);
    }
}
