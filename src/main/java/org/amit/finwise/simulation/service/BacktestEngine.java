package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.simulation.dto.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BacktestEngine {

    private final EodPriceRepository eodRepo;

    public record BacktestResult(
            List<ChartPoint> history,
            BigDecimal totalInvested,
            BigDecimal finalValue,
            LocalDate dataFrom
    ) {}

    public BacktestResult replay(SimulationRequest req) {
        List<EodPrice> prices = eodRepo
                .findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate(
                        req.symbol().toUpperCase(), req.startDate());

        if (prices.isEmpty()) {
            return new BacktestResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, req.startDate());
        }

        return req.investmentMode() == InvestmentMode.LUMPSUM
                ? replayLumpsum(req, prices)
                : replaySip(req, prices);
    }

    // ── LUMPSUM ──────────────────────────────────────────────────────────────

    private BacktestResult replayLumpsum(SimulationRequest req, List<EodPrice> prices) {
        BigDecimal firstPrice = prices.get(0).getAdjClose();
        if (firstPrice == null || firstPrice.compareTo(BigDecimal.ZERO) == 0) {
            return new BacktestResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, req.startDate());
        }

        BigDecimal units = req.amount().divide(firstPrice, 6, RoundingMode.HALF_UP);
        List<ChartPoint> history = new ArrayList<>();
        for (EodPrice p : prices) {
            if (p.getAdjClose() == null) continue;
            history.add(new ChartPoint(p.getTradeDate(),
                    units.multiply(p.getAdjClose()).setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal finalValue = history.isEmpty() ? BigDecimal.ZERO
                : history.get(history.size() - 1).value();
        return new BacktestResult(history, req.amount(), finalValue, prices.get(0).getTradeDate());
    }

    // ── SIP ──────────────────────────────────────────────────────────────────

    private BacktestResult replaySip(SimulationRequest req, List<EodPrice> prices) {
        Map<YearMonth, List<EodPrice>> byMonth = new LinkedHashMap<>();
        for (EodPrice p : prices) {
            byMonth.computeIfAbsent(YearMonth.from(p.getTradeDate()), k -> new ArrayList<>()).add(p);
        }

        BigDecimal totalUnits = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        MathContext mc = new MathContext(10, RoundingMode.HALF_UP);

        for (List<EodPrice> monthPrices : byMonth.values()) {
            EodPrice buyDay = monthPrices.get(0);
            if (buyDay.getAdjClose() == null || buyDay.getAdjClose().compareTo(BigDecimal.ZERO) == 0)
                continue;
            totalUnits = totalUnits.add(req.amount().divide(buyDay.getAdjClose(), mc));
            totalInvested = totalInvested.add(req.amount());
        }

        List<ChartPoint> history = new ArrayList<>();
        for (EodPrice p : prices) {
            if (p.getAdjClose() == null) continue;
            history.add(new ChartPoint(p.getTradeDate(),
                    totalUnits.multiply(p.getAdjClose()).setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal finalValue = history.isEmpty() ? BigDecimal.ZERO
                : history.get(history.size() - 1).value();
        return new BacktestResult(history, totalInvested, finalValue, prices.get(0).getTradeDate());
    }
}
