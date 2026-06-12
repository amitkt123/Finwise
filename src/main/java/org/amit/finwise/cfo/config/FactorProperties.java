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
}
