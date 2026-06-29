package org.amit.finwise.simulation.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.simulation.dto.*;
import org.amit.finwise.simulation.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final SimulationOrchestrator orchestrator;
    private final BacktestEngine backtestEngine;
    private final EventAnnotationService eventAnnotationService;
    private final FactorAttributionEngine factorAttributionEngine;

    @PostMapping("/run")
    public ResponseEntity<SimulationResponse> run(@RequestBody SimulationRequest req) {
        return ResponseEntity.ok(orchestrator.run(req));
    }

    @GetMapping("/portfolio/{userId}/forward")
    public ResponseEntity<SimulationResponse> portfolioForward(
            @PathVariable String userId,
            @RequestParam(defaultValue = "60") int projectionMonths) {
        return ResponseEntity.ok(orchestrator.runForPortfolio(userId, projectionMonths));
    }

    @GetMapping("/company/{symbol}/history")
    public ResponseEntity<CompanyHistoryResponse> companyHistory(
            @PathVariable String symbol,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {

        var histReq = new SimulationRequest(symbol, InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(100_000), from, 0);
        var btResult = backtestEngine.replay(histReq);
        List<EventAnnotation> annotations = eventAnnotationService.annotate(symbol, from, to);
        FactorAttribution attribution = factorAttributionEngine.attribute(symbol, from);

        return ResponseEntity.ok(new CompanyHistoryResponse(
                symbol, btResult.history(), annotations, attribution));
    }

    public record CompanyHistoryResponse(
            String symbol,
            List<ChartPoint> history,
            List<EventAnnotation> annotations,
            FactorAttribution factorAttribution
    ) {}
}
