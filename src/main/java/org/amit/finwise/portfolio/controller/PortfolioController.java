package org.amit.finwise.portfolio.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.AttributionReport;
import org.amit.finwise.cfo.model.LookThroughResult;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.VarBacktestReport;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.LookThroughService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.PortfolioValueSeriesService;
import org.amit.finwise.cfo.service.analytics.PortfolioValueSeriesService.PortfolioValueSeriesResponse;
import org.amit.finwise.cfo.service.analytics.PortfolioValueSeriesService.Range;
import org.amit.finwise.cfo.service.analytics.VarBacktestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final PortfolioRiskService portfolioRiskService;
    private final VarBacktestService varBacktestService;
    private final AttributionService attributionService;
    private final LookThroughService lookThroughService;
    private final PortfolioValueSeriesService valueSeriesService;

    // ── 1A: Portfolio value time-series ─────────────────────────────────────

    @GetMapping("/performance/series")
    public ResponseEntity<PortfolioValueSeriesResponse> performanceSeries(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "1Y") String range) {
        Range r = switch (range.toUpperCase()) {
            case "1M" -> Range.ONE_MONTH;
            case "6M" -> Range.SIX_MONTHS;
            case "ALL" -> Range.ALL;
            default -> Range.ONE_YEAR;
        };
        return ResponseEntity.ok(valueSeriesService.series(principal.getUsername(), r));
    }

    // ── 1B: VaR summary ─────────────────────────────────────────────────────

    @GetMapping("/risk/var")
    public ResponseEntity<VarSummaryResponse> getVar(
            @AuthenticationPrincipal UserDetails principal) {
        return portfolioRiskService.compute(principal.getUsername())
                .map(rd -> ResponseEntity.ok(new VarSummaryResponse(List.of(
                        new VarMethodEntry("Parametric", rd.var95Parametric(), "95%"),
                        new VarMethodEntry("Cornish-Fisher", rd.var95CornishFisher(), "95%"),
                        new VarMethodEntry("Historical", rd.var95Historical(), "95%"),
                        new VarMethodEntry("CVaR", rd.cvar95(), "95%")
                ))))
                .orElse(ResponseEntity.status(503).build());
    }

    // ── 1C: VaR backtest ────────────────────────────────────────────────────

    @GetMapping("/risk/backtest")
    public ResponseEntity<List<VarBacktestReport>> getBacktest(
            @AuthenticationPrincipal UserDetails principal) {
        List<VarBacktestReport> reports = varBacktestService.backtest(principal.getUsername());
        if (reports.isEmpty()) return ResponseEntity.status(503).build();
        return ResponseEntity.ok(reports);
    }

    // ── 1D: Risk contributors ────────────────────────────────────────────────

    @GetMapping("/risk/contributors")
    public ResponseEntity<ContributorsResponse> getContributors(
            @AuthenticationPrincipal UserDetails principal) {
        return portfolioRiskService.compute(principal.getUsername())
                .map(rd -> ResponseEntity.ok(new ContributorsResponse(rd.riskContributors())))
                .orElse(ResponseEntity.status(503).build());
    }

    // ── 1E: Brinson-Fachler attribution ─────────────────────────────────────

    @GetMapping("/attribution")
    public ResponseEntity<AttributionReport> getAttribution(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) String window) {
        return attributionService.compute(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── 1F: MF look-through ──────────────────────────────────────────────────

    @GetMapping("/look-through")
    public ResponseEntity<LookThroughResult> getLookThrough(
            @AuthenticationPrincipal UserDetails principal) {
        return lookThroughService.compute(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    record VarSummaryResponse(List<VarMethodEntry> methods) {}
    record VarMethodEntry(String label, double value, String confidence) {}
    record ContributorsResponse(List<RiskDecomposition.RiskContributor> contributors) {}
}
