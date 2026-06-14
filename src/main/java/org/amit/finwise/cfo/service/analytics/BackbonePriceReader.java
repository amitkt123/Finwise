package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.model.DataQualityFlag;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Single read path from the market-wide DF-1 backbone into the
 * {@link StockPriceHistory} shape the analytics layer already consumes:
 * equities from {@code md_eod_price}, indices from {@code md_index_eod}.
 *
 * <p>This is the seam that moves the risk engine, factor model, technicals,
 * liquidity and the benchmark off the holdings-only Yahoo table and onto
 * official exchange EOD data — market-wide and seeded 6.5y, so a freshly
 * bought symbol has full history immediately (no cold-start backfill).
 *
 * <p>Equity rows carry our own back-adjusted close ({@code adj_close}) so
 * splits/bonuses don't poison returns; index levels need no adjustment. The
 * returned {@code StockPriceHistory} instances are detached projections (never
 * persisted) flagged {@link DataQualityFlag#OK}, since corporate-action level
 * shifts are already absorbed into {@code adj_close}.
 *
 * <p>Symbols starting with {@code '^'} are index tickers, resolved to NSE index
 * names via {@link FactorProperties#nseIndexName(String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackbonePriceReader {

    private final EodPriceRepository eodRepo;
    private final IndexEodRepository indexRepo;
    private final FactorProperties factorProperties;

    /** Backbone daily series for one symbol (equity or {@code '^'}-index), ascending by date. */
    public List<StockPriceHistory> dailySeries(String symbol, LocalDate since) {
        if (symbol == null || symbol.isBlank()) return List.of();
        String s = symbol.toUpperCase();
        if (isIndex(s)) return indexSeries(s, since);
        return eodRepo.findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate(s, since).stream()
                .map(BackbonePriceReader::fromEod)
                .toList();
    }

    /**
     * Backbone daily series for many symbols, keyed by the caller's original symbol
     * string, each list ascending by date. Equities are fetched in a single batch
     * query; index tickers are resolved individually against md_index_eod.
     */
    public Map<String, List<StockPriceHistory>> dailySeries(List<String> symbols, LocalDate since) {
        Map<String, List<StockPriceHistory>> out = new LinkedHashMap<>();
        List<String> equities = new ArrayList<>();
        for (String raw : symbols) {
            if (raw == null || raw.isBlank()) continue;
            if (isIndex(raw.toUpperCase())) out.put(raw, indexSeries(raw.toUpperCase(), since));
            else equities.add(raw.toUpperCase());
        }

        if (!equities.isEmpty()) {
            Map<String, List<StockPriceHistory>> bySymbol =
                    eodRepo.findBySymbolsSince(equities, since).stream()
                            .map(BackbonePriceReader::fromEod)
                            .collect(Collectors.groupingBy(StockPriceHistory::getSymbol,
                                    LinkedHashMap::new, Collectors.toList()));
            for (String raw : symbols) {
                if (raw == null || raw.isBlank()) continue;
                String s = raw.toUpperCase();
                if (!isIndex(s)) out.put(raw, bySymbol.getOrDefault(s, List.of()));
            }
        }
        return out;
    }

    private List<StockPriceHistory> indexSeries(String ticker, LocalDate since) {
        String name = factorProperties.nseIndexName(ticker);
        List<IndexEod> rows = indexRepo
                .findByIndexNameIgnoreCaseAndTradeDateGreaterThanEqualOrderByTradeDate(name, since);
        if (rows.isEmpty()) {
            log.debug("[Backbone] no md_index_eod rows for {} (resolved '{}')", ticker, name);
        }
        return rows.stream().map(r -> fromIndex(ticker, r)).toList();
    }

    private static boolean isIndex(String symbol) {
        return symbol.startsWith("^");
    }

    private static StockPriceHistory fromEod(EodPrice e) {
        BigDecimal adj = e.getAdjClose() != null ? e.getAdjClose() : e.getClose();
        return StockPriceHistory.builder()
                .symbol(e.getSymbol())
                .priceDate(e.getTradeDate())
                .openPrice(e.getOpen())
                .highPrice(e.getHigh())
                .lowPrice(e.getLow())
                .closePrice(e.getClose())
                .adjustedClose(adj)
                .volume(e.getVolume())
                .dataQualityFlag(DataQualityFlag.OK)
                .build();
    }

    private static StockPriceHistory fromIndex(String ticker, IndexEod r) {
        return StockPriceHistory.builder()
                .symbol(ticker)
                .priceDate(r.getTradeDate())
                .openPrice(r.getOpen())
                .highPrice(r.getHigh())
                .lowPrice(r.getLow())
                .closePrice(r.getClose())
                .adjustedClose(r.getClose())
                .volume(r.getVolume())
                .dataQualityFlag(DataQualityFlag.OK)
                .build();
    }
}
