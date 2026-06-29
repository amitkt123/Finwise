package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FactorAttributionEngineTest {

    @Mock ReturnSeriesService returnSeriesService;
    @InjectMocks FactorAttributionEngine engine;

    @Test
    void marketBetaAndAlphaSumToTotalReturn() {
        NavigableMap<LocalDate, Double> mkt = new TreeMap<>();
        NavigableMap<LocalDate, Double> stock = new TreeMap<>();
        LocalDate d = LocalDate.of(2022, 1, 3);
        double[] mktRets = {0.01, -0.02, 0.015, -0.005, 0.02};
        for (double r : mktRets) {
            mkt.put(d, r);
            stock.put(d, 1.5 * r + 0.001);
            d = d.plusDays(1);
        }
        when(returnSeriesService.getReturnSeries(any(), any()))
                .thenReturn(Map.of("INFY", stock, "^NSEI", mkt));

        var result = engine.attribute("INFY", LocalDate.of(2022, 1, 3));

        double sumComponents = result.marketBetaPct() + result.alphaPct() + result.unexplainedPct();
        assertEquals(result.periodReturnPct(), sumComponents, 0.01,
                "Components must sum to total return");
    }

    @Test
    void returnsZeroAttributionForUnknownSymbol() {
        when(returnSeriesService.getReturnSeries(any(), any())).thenReturn(Map.of());
        var result = engine.attribute("UNKNOWN", LocalDate.now().minusYears(1));
        assertEquals(0.0, result.periodReturnPct(), 0.001);
    }
}
