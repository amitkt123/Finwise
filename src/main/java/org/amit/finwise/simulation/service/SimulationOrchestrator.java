package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService;
import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService.CashFlow;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.simulation.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationOrchestrator {

    private final BacktestEngine backtestEngine;
    private final ForwardProjectionEngine forwardProjectionEngine;
    private final EventAnnotationService eventAnnotationService;
    private final FactorAttributionEngine factorAttributionEngine;
    private final InvestmentRepository investmentRepository;
    private final MoneyWeightedReturnService moneyWeightedReturnService;

    public SimulationResponse run(SimulationRequest req) {
        int cappedMonths = Math.min(req.projectionMonths(), 120);

        CompletableFuture<BacktestEngine.BacktestResult> btFuture =
                CompletableFuture.supplyAsync(() -> backtestEngine.replay(req));

        CompletableFuture<ProjectionResult> projFuture = btFuture.thenApplyAsync(bt ->
                forwardProjectionEngine.project(req.symbol(),
                        bt.finalValue().compareTo(BigDecimal.ZERO) > 0
                                ? bt.finalValue() : req.amount(),
                        cappedMonths));

        CompletableFuture<List<EventAnnotation>> annoFuture =
                CompletableFuture.supplyAsync(() ->
                        eventAnnotationService.annotate(req.symbol(),
                                req.startDate(), LocalDate.now()));

        CompletableFuture<FactorAttribution> attrFuture =
                CompletableFuture.supplyAsync(() ->
                        factorAttributionEngine.attribute(req.symbol(), req.startDate()));

        BacktestEngine.BacktestResult bt = btFuture.join();
        ProjectionResult proj             = projFuture.join();
        List<EventAnnotation> annotations = annoFuture.join();
        FactorAttribution attribution      = attrFuture.join();

        double xirr = computeXirr(req, bt);
        double cagr = computeCagr(bt.totalInvested(), bt.finalValue(), req.startDate());
        double absReturn = absReturnPct(bt.totalInvested(), bt.finalValue());

        List<String> warnings = new ArrayList<>();
        if (proj.monteCarlo().isEmpty() && !bt.history().isEmpty()) {
            warnings.add("INSUFFICIENT_HISTORY_FOR_MC");
        }

        SimulationSummary summary = new SimulationSummary(
                req.symbol(), req.instrumentType(), req.investmentMode(),
                bt.totalInvested(), bt.finalValue(),
                round(absReturn), round(cagr), round(xirr),
                bt.dataFrom(), warnings, List.of());

        return new SimulationResponse(summary, bt.history(), proj, annotations, attribution);
    }

    public SimulationResponse runForPortfolio(String userId, int months) {
        int cappedMonths = Math.min(months, 120);
        List<Investment> holdings = investmentRepository.findActiveInvestments(userId).stream()
                .filter(inv -> inv.getSymbol() != null)
                .toList();

        if (holdings.isEmpty()) {
            var empty = new SimulationSummary(userId, InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0,
                    LocalDate.now(), List.of("NO_HOLDINGS"), List.of());
            return new SimulationResponse(empty, List.of(),
                    new ProjectionResult(List.of(), List.of()), List.of(),
                    new FactorAttribution(0, 0, 0, 0));
        }

        BigDecimal totalInvested    = BigDecimal.ZERO;
        BigDecimal totalCurrentValue = BigDecimal.ZERO;
        List<String> skipped = new ArrayList<>();
        LocalDate earliest = LocalDate.now();

        for (Investment inv : holdings) {
            var invReq = new SimulationRequest(inv.getSymbol(), InstrumentType.STOCK,
                    InvestmentMode.LUMPSUM, inv.getTotalCost(), inv.getPurchaseDate(), cappedMonths);
            var bt = backtestEngine.replay(invReq);
            if (bt.history().isEmpty()) { skipped.add(inv.getSymbol()); continue; }
            totalInvested     = totalInvested.add(bt.totalInvested());
            totalCurrentValue = totalCurrentValue.add(bt.finalValue());
            if (inv.getPurchaseDate().isBefore(earliest)) earliest = inv.getPurchaseDate();
        }

        var proj    = forwardProjectionEngine.project("^NSEI", totalCurrentValue, cappedMonths);
        double cagr = computeCagr(totalInvested, totalCurrentValue, earliest);
        double abs  = absReturnPct(totalInvested, totalCurrentValue);

        SimulationSummary summary = new SimulationSummary(
                userId, InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                totalInvested, totalCurrentValue, round(abs), round(cagr), 0,
                earliest, List.of(), skipped);

        return new SimulationResponse(summary, List.of(), proj, List.of(),
                new FactorAttribution(round(abs), 0, 0, 0));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private double computeXirr(SimulationRequest req, BacktestEngine.BacktestResult bt) {
        if (bt.totalInvested().compareTo(BigDecimal.ZERO) == 0 || bt.history().isEmpty()) return 0;
        List<CashFlow> flows = List.of(
                new CashFlow(req.startDate(), -bt.totalInvested().doubleValue()),
                new CashFlow(LocalDate.now(), bt.finalValue().doubleValue()));
        return moneyWeightedReturnService.solve(flows)
                .map(r -> r.xirr() * 100).orElse(0.0);
    }

    private double computeCagr(BigDecimal invested, BigDecimal finalVal, LocalDate from) {
        if (invested.compareTo(BigDecimal.ZERO) == 0) return 0;
        long days = ChronoUnit.DAYS.between(from, LocalDate.now());
        if (days <= 0) return 0;
        return (Math.pow(finalVal.doubleValue() / invested.doubleValue(), 365.25 / days) - 1) * 100;
    }

    private double absReturnPct(BigDecimal invested, BigDecimal finalVal) {
        if (invested.compareTo(BigDecimal.ZERO) == 0) return 0;
        return finalVal.subtract(invested)
                .divide(invested, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
