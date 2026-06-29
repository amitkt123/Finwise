package org.amit.finwise.simulation.service;

import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.simulation.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationOrchestratorTest {

    @Mock BacktestEngine backtestEngine;
    @Mock ForwardProjectionEngine forwardProjectionEngine;
    @Mock EventAnnotationService eventAnnotationService;
    @Mock FactorAttributionEngine factorAttributionEngine;
    @Mock InvestmentRepository investmentRepository;
    @Mock MoneyWeightedReturnService moneyWeightedReturnService;
    @InjectMocks SimulationOrchestrator orchestrator;

    @Test
    void runAssemblesFullResponse() {
        var req = new SimulationRequest("INFY", InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(100_000), LocalDate.of(2023, 1, 2), 12);

        var history = List.of(new ChartPoint(LocalDate.of(2023, 1, 2), BigDecimal.valueOf(100_000)));
        var btResult = new BacktestEngine.BacktestResult(history, BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(115_000), LocalDate.of(2023, 1, 2));
        when(backtestEngine.replay(req)).thenReturn(btResult);

        when(forwardProjectionEngine.project(eq("INFY"), any(), eq(12)))
                .thenReturn(new ProjectionResult(List.of(), List.of()));
        when(eventAnnotationService.annotate(eq("INFY"), any(), any()))
                .thenReturn(List.of());
        when(factorAttributionEngine.attribute(eq("INFY"), any()))
                .thenReturn(new FactorAttribution(15.0, 10.0, 3.0, 2.0));
        when(moneyWeightedReturnService.solve(any())).thenReturn(Optional.empty());

        var response = orchestrator.run(req);

        assertNotNull(response);
        assertNotNull(response.summary());
        assertEquals("INFY", response.summary().symbol());
        assertEquals(1, response.history().size());
        assertNotNull(response.projection());
        assertNotNull(response.annotations());
        assertNotNull(response.factorAttribution());
        assertEquals(15.0, response.factorAttribution().periodReturnPct(), 0.001);
    }
}
