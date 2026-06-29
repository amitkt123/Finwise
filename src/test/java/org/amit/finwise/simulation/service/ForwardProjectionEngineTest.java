package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.model.VolForecast;
import org.amit.finwise.cfo.service.analytics.GarchService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.simulation.dto.ScenarioBand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    @InjectMocks ForwardProjectionEngine engine;

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
