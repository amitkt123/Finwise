package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScenarioBandServiceTest {

    @Mock ReturnSeriesService returnSeriesService;
    @InjectMocks ScenarioBandService service;

    @Test
    void bandsObeyOptimisticNeutralPessimisticOrder() {
        NavigableMap<LocalDate, Double> returns = new TreeMap<>();
        LocalDate d = LocalDate.of(2022, 1, 3);
        for (int i = 0; i < 120; i++) {
            returns.put(d, i % 2 == 0 ? 0.02 : -0.01);
            d = d.plusDays(1);
        }
        when(returnSeriesService.getReturnSeries(eq(List.of("INFY")), any()))
                .thenReturn(Map.of("INFY", returns));

        var result = service.project("INFY", BigDecimal.valueOf(100_000), 6);

        assertTrue(result.sufficientHistory());
        assertFalse(result.bands().isEmpty());
        for (var band : result.bands()) {
            assertTrue(band.optimistic().compareTo(band.neutral()) >= 0,
                    "optimistic >= neutral");
            assertTrue(band.neutral().compareTo(band.pessimistic()) >= 0,
                    "neutral >= pessimistic");
        }
    }

    @Test
    void insufficientHistoryFlaggedWhenFewerThan60Days() {
        NavigableMap<LocalDate, Double> sparse = new TreeMap<>();
        sparse.put(LocalDate.now(), 0.01);
        when(returnSeriesService.getReturnSeries(eq(List.of("TINY")), any()))
                .thenReturn(Map.of("TINY", sparse));

        var result = service.project("TINY", BigDecimal.valueOf(10_000), 12);

        assertFalse(result.sufficientHistory());
        assertTrue(result.bands().isEmpty());
    }
}
