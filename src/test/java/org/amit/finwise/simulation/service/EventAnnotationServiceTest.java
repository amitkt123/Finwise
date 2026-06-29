package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.cfo.service.macro.RegimeModelService;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.simulation.dto.AnnotationType;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventAnnotationServiceTest {

    @Mock MacroSeriesService macroSeriesService;
    @Mock CorporateActionRepository corporateActionRepository;
    @Mock RegimeModelService regimeModelService;
    @Mock ReturnSeriesService returnSeriesService;
    @Mock IndexEodRepository indexEodRepository;
    @InjectMocks EventAnnotationService service;

    @Test
    void repoRateChangeProducesMacroAnnotation() {
        LocalDate from = LocalDate.of(2023, 1, 1);
        LocalDate to   = LocalDate.of(2023, 12, 31);

        when(macroSeriesService.valueAsOf(any(MacroSeriesCode.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    LocalDate d = inv.getArgument(1);
                    if (d.equals(LocalDate.of(2023, 1, 1)))  return Optional.of(BigDecimal.valueOf(6.25));
                    if (d.equals(LocalDate.of(2023, 6, 1)))  return Optional.of(BigDecimal.valueOf(6.50));
                    return Optional.empty();
                });

        when(corporateActionRepository.findAll()).thenReturn(List.of());
        when(returnSeriesService.getReturnSeries(any(), any())).thenReturn(Map.of());
        when(indexEodRepository.findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(
                any(), any(), any())).thenReturn(List.of());

        var annotations = service.annotate("INFY", from, to);

        assertNotNull(annotations);
        assertTrue(annotations.stream().anyMatch(a -> a.type() == AnnotationType.MACRO),
                "Expected at least one MACRO annotation for rate change");
    }

    @Test
    void returnsEmptyListWhenAllSourcesEmpty() {
        when(corporateActionRepository.findAll()).thenReturn(List.of());
        when(returnSeriesService.getReturnSeries(any(), any())).thenReturn(Map.of());
        when(indexEodRepository.findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(
                any(), any(), any())).thenReturn(List.of());
        when(macroSeriesService.valueAsOf(any(), any())).thenReturn(Optional.empty());

        var result = service.annotate("INFY", LocalDate.now().minusYears(1), LocalDate.now());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
