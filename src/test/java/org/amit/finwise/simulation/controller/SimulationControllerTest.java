package org.amit.finwise.simulation.controller;

import org.amit.finwise.simulation.dto.*;
import org.amit.finwise.simulation.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SimulationControllerTest {

    @Mock SimulationOrchestrator orchestrator;
    @Mock BacktestEngine backtestEngine;
    @Mock EventAnnotationService eventAnnotationService;
    @Mock FactorAttributionEngine factorAttributionEngine;
    @InjectMocks SimulationController controller;

    @Test
    void runDelegatesToOrchestrator() {
        var req = new SimulationRequest("INFY", InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(50_000), LocalDate.of(2023, 1, 1), 12);

        var summary = new SimulationSummary("INFY", InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(50_000), BigDecimal.valueOf(60_000),
                20.0, 15.0, 16.0, LocalDate.of(2023, 1, 2), List.of(), List.of());
        var expected = new SimulationResponse(summary, List.of(),
                new ProjectionResult(List.of(), List.of()), List.of(),
                new FactorAttribution(20.0, 14.0, 4.0, 2.0));
        when(orchestrator.run(req)).thenReturn(expected);

        var response = controller.run(req);

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
        verify(orchestrator).run(req);
    }

    @Test
    void portfolioForwardDelegatesToOrchestrator() {
        when(orchestrator.runForPortfolio("user1", 60)).thenReturn(
                new SimulationResponse(null, List.of(),
                        new ProjectionResult(List.of(), List.of()),
                        List.of(), new FactorAttribution(0, 0, 0, 0)));

        var response = controller.portfolioForward("user1", 60);
        assertEquals(200, response.getStatusCode().value());
        verify(orchestrator).runForPortfolio("user1", 60);
    }

    @Test
    void companyHistoryReturnsPriceSeriesAndAnnotations() {
        LocalDate from = LocalDate.of(2023, 1, 1), to = LocalDate.of(2023, 12, 31);
        var btResult = new BacktestEngine.BacktestResult(
                List.of(new ChartPoint(from, BigDecimal.valueOf(100_000))),
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(110_000), from);
        when(backtestEngine.replay(any())).thenReturn(btResult);
        when(eventAnnotationService.annotate(eq("RELIANCE"), eq(from), eq(to))).thenReturn(List.of());
        when(factorAttributionEngine.attribute(eq("RELIANCE"), eq(from)))
                .thenReturn(new FactorAttribution(10.0, 7.0, 2.0, 1.0));

        var response = controller.companyHistory("RELIANCE", from, to);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("RELIANCE", response.getBody().symbol());
        assertEquals(1, response.getBody().history().size());
    }
}
