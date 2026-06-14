package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.DataQualityFlag;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Produces canonical daily simple-return series from the market-wide DF-1 backbone
 * (via {@link BackbonePriceReader}: md_eod_price for stocks, md_index_eod for indices).
 *
 * Return formula: r_t = P_t / P_{t-1} − 1 using adjustedClose (fallback closePrice).
 * Backbone closes are already corporate-action back-adjusted, so split/bonus level
 * shifts don't poison returns; any point still flagged excluded is dropped defensively.
 *
 * All risk math in the system must consume returns from this single service — no
 * duplicated return computation elsewhere.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnSeriesService {

    private final BackbonePriceReader backbone;

    /** Minimum number of valid observations required to include a symbol in risk calcs. */
    public static final int MIN_OBSERVATIONS = 60;

    /**
     * Returns daily simple returns for each symbol over [since, today].
     * Symbols with fewer than MIN_OBSERVATIONS valid returns are omitted from the result.
     *
     * @param symbols NSE symbols (uppercase) including optionally the benchmark (^NSEI)
     * @param since   start of the look-back window
     * @return map of symbol → TreeMap&lt;date, return&gt; (ascending date order)
     */
    public Map<String, NavigableMap<LocalDate, Double>> getReturnSeries(
            List<String> symbols, LocalDate since) {

        Map<String, List<StockPriceHistory>> bySymbol = backbone.dailySeries(symbols, since);

        Map<String, NavigableMap<LocalDate, Double>> result = new LinkedHashMap<>();

        for (String symbol : symbols) {
            List<StockPriceHistory> history = bySymbol.getOrDefault(symbol, List.of());
            if (history.size() < 2) {
                log.debug("[ReturnSeries] {} — only {} records, skipping", symbol, history.size());
                continue;
            }

            List<StockPriceHistory> sorted = history.stream()
                    .sorted(Comparator.comparing(StockPriceHistory::getPriceDate))
                    .toList();

            NavigableMap<LocalDate, Double> returns = new TreeMap<>();
            for (int i = 1; i < sorted.size(); i++) {
                StockPriceHistory curr = sorted.get(i);
                StockPriceHistory prev = sorted.get(i - 1);

                // Exclude points whose one-day return is spurious: unexplained gaps and
                // corporate-action level shifts (split/bonus ex-dates) alike.
                DataQualityFlag flag = curr.getDataQualityFlag();
                if (flag != null && flag.isExcludedFromReturns()) continue;

                BigDecimal currPrice = curr.getAdjustedClose() != null
                        ? curr.getAdjustedClose() : curr.getClosePrice();
                BigDecimal prevPrice = prev.getAdjustedClose() != null
                        ? prev.getAdjustedClose() : prev.getClosePrice();

                if (currPrice == null || prevPrice == null
                        || prevPrice.compareTo(BigDecimal.ZERO) == 0) continue;

                double r = currPrice.doubleValue() / prevPrice.doubleValue() - 1.0;
                returns.put(curr.getPriceDate(), r);
            }

            if (returns.size() >= MIN_OBSERVATIONS) {
                result.put(symbol, returns);
            } else {
                log.debug("[ReturnSeries] {} — {} valid observations < min {}, marked INSUFFICIENT_HISTORY",
                        symbol, returns.size(), MIN_OBSERVATIONS);
            }
        }

        return result;
    }
}
