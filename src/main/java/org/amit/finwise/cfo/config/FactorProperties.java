package org.amit.finwise.cfo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-factor risk model configuration (prefix {@code cfo.factors}).
 *
 * Indices are Yahoo Finance tickers for NSE indices, fetched daily into
 * StockPriceHistory alongside the Nifty benchmark. Style indices are
 * unreliable on Yahoo — tickers that return no data are dropped silently
 * and the model degrades to the factors that remain (MKT + SIZE + SECTOR).
 *
 * Factor construction (FactorReturnService):
 *   MKT      = r(^NSEI)
 *   SIZE     = r(midcap-index) − r(^NSEI)        — spread, to decollinearize
 *   SECTOR_k = r(sector index k) − r(^NSEI)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cfo.factors")
public class FactorProperties {

    /** Yahoo tickers fetched into StockPriceHistory by the 16:00 price job. */
    private List<String> indices = List.of(
            "^NSEI", "^NSEBANK", "^CNXIT", "^CNXPHARMA", "^CNXFMCG",
            "^CNXAUTO", "^CNXMETAL", "^CNXENERGY", "^NSEMDCP50", "^CNXSC");

    /** Index whose spread over Nifty defines the SIZE factor. */
    private String midcapIndex = "^NSEMDCP50";

    /** Calendar-day lookback for index ingestion and factor regressions. */
    private int lookbackDays = 730;

    /** Minimum aligned observations to include a holding in the regression. */
    private int minObservations = 120;

    /**
     * Gazetteer sector → sector index. Sectors without an entry (Telecom,
     * Aviation, Infrastructure, PSU) regress on MKT + SIZE only.
     * Override via cfo.factors.sector-index.&lt;Sector&gt;=&lt;ticker&gt;.
     */
    private Map<String, String> sectorIndex = Map.ofEntries(
            Map.entry("Banking", "^NSEBANK"),
            Map.entry("Financial Services", "^NSEBANK"),
            Map.entry("Fintech", "^NSEBANK"),
            Map.entry("IT", "^CNXIT"),
            Map.entry("Internet", "^CNXIT"),
            Map.entry("Pharma", "^CNXPHARMA"),
            Map.entry("Healthcare", "^CNXPHARMA"),
            Map.entry("FMCG", "^CNXFMCG"),
            Map.entry("Consumer", "^CNXFMCG"),
            Map.entry("Retail", "^CNXFMCG"),
            Map.entry("Auto", "^CNXAUTO"),
            Map.entry("Metals", "^CNXMETAL"),
            Map.entry("Energy", "^CNXENERGY"),
            Map.entry("Power", "^CNXENERGY"));

    /** Case-insensitive sector → index lookup. */
    public Optional<String> indexForSector(String sector) {
        if (sector == null || sector.isBlank()) return Optional.empty();
        return sectorIndex.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(sector.trim()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * Yahoo index ticker → NSE index name as published in {@code ind_close_all}
     * (md_index_eod.index_name). This is the glue that lets the risk/factor math —
     * which keys indices by Yahoo ticker — read the official DF-1 index backbone.
     * Override via {@code cfo.factors.index-name.<ticker>=<NSE name>}.
     */
    private Map<String, String> indexName = Map.ofEntries(
            Map.entry("^NSEI", "Nifty 50"),
            Map.entry("^NSEBANK", "Nifty Bank"),
            Map.entry("^CNXIT", "Nifty IT"),
            Map.entry("^CNXPHARMA", "Nifty Pharma"),
            Map.entry("^CNXFMCG", "Nifty FMCG"),
            Map.entry("^CNXAUTO", "Nifty Auto"),
            Map.entry("^CNXMETAL", "Nifty Metal"),
            Map.entry("^CNXENERGY", "Nifty Energy"),
            Map.entry("^NSEMDCP50", "Nifty Midcap 50"),
            Map.entry("^CNXSC", "Nifty Smallcap 100"));

    /**
     * Resolve an index ticker to its NSE index name for md_index_eod lookups.
     * Unknown tickers fall through unchanged (an unmapped index simply finds no
     * rows and the factor model degrades gracefully, as it already does).
     */
    public String nseIndexName(String ticker) {
        if (ticker == null) return null;
        return indexName.getOrDefault(ticker.toUpperCase(), ticker);
    }
}
