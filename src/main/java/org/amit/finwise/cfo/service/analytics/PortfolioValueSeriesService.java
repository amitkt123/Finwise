package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Reconstructs a daily portfolio value time-series over a requested lookback window.
 *
 * For each trading day in the range, each holding contributes its quantity × closing price
 * only from its purchaseDate onward.  BackbonePriceReader is used so the series is backed
 * by the official exchange EOD backbone (never the holdings-only Yahoo cache).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioValueSeriesService {

    private final InvestmentRepository investmentRepository;
    private final BackbonePriceReader backboneReader;

    public record PortfolioValuePoint(LocalDate date, BigDecimal value) {}
    public record PortfolioValueSeriesResponse(List<PortfolioValuePoint> points) {}

    public enum Range { ONE_MONTH, SIX_MONTHS, ONE_YEAR, ALL }

    public PortfolioValueSeriesResponse series(String userId, Range range) {
        List<Investment> equities = investmentRepository.findActiveInvestments(userId).stream()
                .filter(inv -> inv.getSymbol() != null && !inv.getSymbol().isBlank()
                        && inv.getQuantity() != null
                        && inv.getQuantity().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (equities.isEmpty()) return new PortfolioValueSeriesResponse(List.of());

        LocalDate rangeStart = rangeStart(range, equities);
        List<String> symbols = equities.stream()
                .map(inv -> inv.getSymbol().toUpperCase())
                .distinct().toList();

        Map<String, List<StockPriceHistory>> seriesBySymbol =
                backboneReader.dailySeries(symbols, rangeStart);

        // Collect all trading dates across all symbols
        SortedSet<LocalDate> allDates = new TreeSet<>();
        seriesBySymbol.values().forEach(rows -> rows.forEach(r -> allDates.add(r.getPriceDate())));

        if (allDates.isEmpty()) return new PortfolioValueSeriesResponse(List.of());

        // Build per-symbol price maps (date → closePrice)
        Map<String, Map<LocalDate, BigDecimal>> priceBySymbol = new HashMap<>();
        for (Map.Entry<String, List<StockPriceHistory>> e : seriesBySymbol.entrySet()) {
            Map<LocalDate, BigDecimal> priceMap = new HashMap<>();
            for (StockPriceHistory h : e.getValue()) {
                if (h.getClosePrice() != null) {
                    priceMap.put(h.getPriceDate(), h.getClosePrice());
                }
            }
            priceBySymbol.put(e.getKey().toUpperCase(), priceMap);
        }

        List<PortfolioValuePoint> points = new ArrayList<>();
        for (LocalDate date : allDates) {
            BigDecimal total = BigDecimal.ZERO;
            for (Investment inv : equities) {
                // Only include the holding from its purchaseDate onward
                if (inv.getPurchaseDate() != null && date.isBefore(inv.getPurchaseDate())) continue;
                String sym = inv.getSymbol().toUpperCase();
                BigDecimal price = priceBySymbol.getOrDefault(sym, Map.of()).get(date);
                if (price != null) {
                    total = total.add(inv.getQuantity().multiply(price));
                }
            }
            points.add(new PortfolioValuePoint(date, total));
        }

        return new PortfolioValueSeriesResponse(points);
    }

    private LocalDate rangeStart(Range range, List<Investment> equities) {
        return switch (range) {
            case ONE_MONTH -> LocalDate.now().minusDays(30);
            case SIX_MONTHS -> LocalDate.now().minusDays(180);
            case ONE_YEAR -> LocalDate.now().minusDays(365);
            case ALL -> equities.stream()
                    .map(Investment::getPurchaseDate)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(LocalDate.now().minusDays(365));
        };
    }
}
