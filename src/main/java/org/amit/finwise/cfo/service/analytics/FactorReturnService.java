package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.service.StockPriceService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds daily factor-return series from persisted index price history.
 *
 *   MKT      = r(^NSEI)
 *   SIZE     = r(midcap index) − r(^NSEI)
 *   SECTOR_k = r(sector index k) − r(^NSEI)
 *
 * SIZE and sector factors are spreads over the market so the regressors stay
 * (approximately) decollinearized — raw index returns correlate ~0.9 with Nifty.
 * Sector factors are keyed by their index ticker (e.g. "^CNXIT"); the symbolic
 * names MKT/SIZE are reserved for the two style factors.
 *
 * Missing indices simply don't appear in the result — the factor model degrades
 * to the factors that have data rather than failing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorReturnService {

    public static final String MKT_FACTOR = "MKT";
    public static final String SIZE_FACTOR = "SIZE";

    private final ReturnSeriesService returnSeriesService;
    private final FactorProperties factorProperties;

    /**
     * Builds all available factor series over [since, today].
     * Returns empty when the market series itself is missing — without MKT
     * there is no model.
     */
    public Optional<FactorSet> build(LocalDate since) {
        LinkedHashSet<String> indexSymbols = new LinkedHashSet<>(factorProperties.getIndices());
        indexSymbols.add(StockPriceService.NIFTY_SYMBOL);
        indexSymbols.add(factorProperties.getMidcapIndex());
        indexSymbols.addAll(factorProperties.getSectorIndex().values());

        Map<String, NavigableMap<LocalDate, Double>> indexReturns =
                returnSeriesService.getReturnSeries(new ArrayList<>(indexSymbols), since);

        NavigableMap<LocalDate, Double> mkt = indexReturns.get(StockPriceService.NIFTY_SYMBOL);
        if (mkt == null || mkt.isEmpty()) {
            log.warn("[FactorReturns] No {} history — factor model unavailable",
                    StockPriceService.NIFTY_SYMBOL);
            return Optional.empty();
        }

        NavigableMap<LocalDate, Double> size =
                spreadOverMarket(indexReturns.get(factorProperties.getMidcapIndex()), mkt);
        if (size == null) {
            log.warn("[FactorReturns] Midcap index {} missing — SIZE factor unavailable",
                    factorProperties.getMidcapIndex());
        }

        // Sector spreads keyed by index ticker; multiple gazetteer sectors may map
        // to the same index (Pharma + Healthcare → ^CNXPHARMA) — build each once.
        Map<String, NavigableMap<LocalDate, Double>> sectorSpreads = new LinkedHashMap<>();
        for (String idx : new TreeSet<>(factorProperties.getSectorIndex().values())) {
            NavigableMap<LocalDate, Double> spread = spreadOverMarket(indexReturns.get(idx), mkt);
            if (spread != null) sectorSpreads.put(idx, spread);
        }

        return Optional.of(new FactorSet(mkt, size, sectorSpreads));
    }

    /** Index-minus-market spread on the intersection of dates; null when the index is absent. */
    private static NavigableMap<LocalDate, Double> spreadOverMarket(
            NavigableMap<LocalDate, Double> index, NavigableMap<LocalDate, Double> mkt) {
        if (index == null || index.isEmpty()) return null;
        NavigableMap<LocalDate, Double> spread = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> e : index.entrySet()) {
            Double m = mkt.get(e.getKey());
            if (m != null) spread.put(e.getKey(), e.getValue() - m);
        }
        return spread.isEmpty() ? null : spread;
    }

    /**
     * @param mkt          Nifty 50 daily returns (never null)
     * @param size         midcap-minus-Nifty spread; null when the midcap index has no data
     * @param sectorSpreads sector index ticker → index-minus-Nifty spread (only those with data)
     */
    public record FactorSet(
            NavigableMap<LocalDate, Double> mkt,
            NavigableMap<LocalDate, Double> size,
            Map<String, NavigableMap<LocalDate, Double>> sectorSpreads
    ) {}
}
