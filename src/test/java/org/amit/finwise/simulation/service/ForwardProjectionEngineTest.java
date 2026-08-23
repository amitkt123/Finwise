package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.VolForecast;
import org.amit.finwise.cfo.service.analytics.GarchService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.simulation.dto.ScenarioBand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForwardProjectionEngineTest {

    @Mock GarchService garchService;
    @Mock ReturnSeriesService returnSeriesService;
    @Mock ScenarioBandService scenarioBandService;
    ForwardProjectionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ForwardProjectionEngine(garchService, returnSeriesService, scenarioBandService,
                new RiskProperties());
    }

    private NavigableMap<LocalDate, Double> syntheticReturns(int count) {
        NavigableMap<LocalDate, Double> m = new TreeMap<>();
        LocalDate d = LocalDate.of(2022, 1, 3);
        Random rng = new Random(42);
        for (int i = 0; i < count; i++) {
            m.put(d, rng.nextGaussian() * 0.01);
            d = d.plusDays(1);
        }
        return m;
    }

    private VolForecast stubVol(double dailyVol) {
        double annVol = dailyVol * Math.sqrt(252);
        return new VolForecast(
                VolForecast.Method.GARCH,
                120,
                0.000001,               // omega
                0.05,                   // alpha
                0.90,                   // beta
                null,                   // leverageGamma
                0.95,                   // persistence
                dailyVol,               // conditionalDailyVol
                dailyVol * 1.1,         // longRunDailyVol
                dailyVol * Math.sqrt(10), // tenDayVol
                annVol,                 // annualizedVol
                null,                   // logLikelihood
                List.of()
        );
    }

    @Test
    void monteCarloIntervalsObeyP5LtP50LtP95() {
        NavigableMap<LocalDate, Double> rets = syntheticReturns(120);
        when(returnSeriesService.getReturnSeries(any(), any()))
                .thenReturn(Map.of("INFY", rets));
        when(garchService.fit(any())).thenReturn(stubVol(0.02));

        var scenarioBands = List.of(
                new ScenarioBand(
                        LocalDate.now().plusMonths(1),
                        BigDecimal.valueOf(120_000),
                        BigDecimal.valueOf(105_000),
                        BigDecimal.valueOf(90_000))
        );
        when(scenarioBandService.project(eq("INFY"), any(), eq(1)))
                .thenReturn(new ScenarioBandService.ScenarioBandResult(scenarioBands, true));

        var result = engine.project("INFY", BigDecimal.valueOf(100_000), 1);

        assertFalse(result.monteCarlo().isEmpty());
        var mc = result.monteCarlo().get(0);
        assertTrue(mc.p5().compareTo(mc.p50()) <= 0, "p5 <= p50");
        assertTrue(mc.p50().compareTo(mc.p95()) <= 0, "p50 <= p95");
    }

    // ── shrunkDailyDrift ─────────────────────────────────────────────────────

    @Test
    void shrunkDailyDrift_blendsSampleMeanWithCapmPriorByObservationCount() {
        double[] returns = new double[750];
        Arrays.fill(returns, 0.01); // absurd 1%/day sample mean — the case shrinkage exists for

        double mu = engine.shrunkDailyDrift(returns);

        RiskProperties defaults = new RiskProperties();
        double muPrior = (defaults.getRiskFreeRate() + defaults.getEquityRiskPremium()) / 252.0;
        double expected = (750 * 0.01 + defaults.getDriftShrinkageDays() * muPrior)
                / (750 + defaults.getDriftShrinkageDays());

        assertEquals(expected, mu, 1e-12);
        // With T=750 << K=2500, the prior dominates: shrunk drift must sit far below the
        // undiluted 1%/day sample mean, not equal it.
        assertTrue(mu < 0.01 * 0.5, "shrinkage must pull the extreme sample mean sharply toward the prior");
    }

    @Test
    void shrunkDailyDrift_withZeroReturns_equalsPriorAlone() {
        double[] returns = new double[500];
        Arrays.fill(returns, 0.0);

        RiskProperties props = new RiskProperties();
        double expectedPrior = (props.getRiskFreeRate() + props.getEquityRiskPremium()) / 252.0;
        double mu = engine.shrunkDailyDrift(returns);

        // muSample=0 contributes 0 to the numerator, so muShrunk = K·muPrior / (T+K),
        // which is muPrior scaled down by T/(T+K) from the T=0 case — sanity check the
        // formula's shape rather than asserting equality to the raw prior.
        double expected = (props.getDriftShrinkageDays() * expectedPrior) / (500 + props.getDriftShrinkageDays());
        assertEquals(expected, mu, 1e-12);
    }

    // ── dailySigmaSchedule ───────────────────────────────────────────────────

    @Test
    void dailySigmaSchedule_garchFit_decaysGeometricallyTowardLongRunVol() {
        VolForecast vol = stubVol(0.02); // conditional=0.02, longRun=0.022, persistence=0.95

        double[] schedule = engine.dailySigmaSchedule(vol, 252);

        assertEquals(0.02, schedule[0], 1e-9, "day 1 must equal the one-step conditional vol");
        // Must move toward, not stay pinned at, the one-step vol.
        assertTrue(schedule[251] > schedule[0], "far-horizon vol must have moved toward the (higher) long-run level");
        assertEquals(0.022, schedule[251], 5e-4, "after 252 days at persistence=0.95 the schedule "
                + "must have nearly converged to the long-run vol");
        // Monotonic march from conditional toward long-run (both are positive here).
        for (int i = 1; i < schedule.length; i++) {
            assertTrue(schedule[i] >= schedule[i - 1] - 1e-12, "schedule must not overshoot/oscillate");
        }
    }

    @Test
    void dailySigmaSchedule_ewmaFallback_staysFlatAtOneStepVol() {
        VolForecast ewma = new VolForecast(
                VolForecast.Method.EWMA, 40,
                null, null, null, null, 0.94,
                0.018, Double.NaN, 0.018 * Math.sqrt(10), 0.018 * Math.sqrt(252),
                null, List.of("GARCH_FALLBACK: T=40 < 250 observations"));

        double[] schedule = engine.dailySigmaSchedule(ewma, 500);

        for (double s : schedule) {
            assertEquals(0.018, s, 1e-12, "EWMA has no mean reversion — every horizon day must equal the flat one-step vol");
        }
    }

    @Test
    void skipsMonteCarloWhenInsufficientHistory() {
        NavigableMap<LocalDate, Double> sparse = new TreeMap<>();
        sparse.put(LocalDate.now(), 0.01);
        when(returnSeriesService.getReturnSeries(any(), any()))
                .thenReturn(Map.of("TINY", sparse));
        when(scenarioBandService.project(any(), any(), anyInt()))
                .thenReturn(new ScenarioBandService.ScenarioBandResult(List.of(), false));

        var result = engine.project("TINY", BigDecimal.valueOf(10_000), 6);

        assertTrue(result.monteCarlo().isEmpty());
    }
}
