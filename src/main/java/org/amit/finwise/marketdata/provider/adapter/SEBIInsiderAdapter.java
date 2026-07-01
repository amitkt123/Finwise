package org.amit.finwise.marketdata.provider.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SEBI insider-trading (PIT) disclosures for a symbol. Routed through
 * {@link NseApiClient}, which handles the cookie warm-up NSE requires —
 * a bare RestClient call with just a User-Agent header gets 401/403'd.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SEBIInsiderAdapter implements MarketFeedProvider {

    private final NseApiClient nseApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return "sebi-insider"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.INSIDER_TRADES; }
    @Override public boolean isHealthy() { return true; }

    public DataEnvelope<List<InsiderTrade>> fetchInsiderTrades(String symbol, LocalDate since) {
        try {
            Optional<String> body = nseApiClient.fetchInsiderTrades(symbol, since, LocalDate.now());
            if (body.isEmpty()) {
                return DataEnvelope.missing(name(), "NSE insider trades unavailable for " + symbol);
            }

            Map<?, ?> response = objectMapper.readValue(body.get(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = response != null
                ? (List<Map<String, Object>>) response.get("data") : List.of();
            if (data == null) return DataEnvelope.of(List.of(), name(), DataQuality.EOD);

            List<InsiderTrade> trades = data.stream().map(t -> new InsiderTrade(
                symbol,
                (String) t.getOrDefault("acqName", ""),
                (String) t.getOrDefault("personCategory", ""),
                (String) t.getOrDefault("tdpTransactionType", ""),
                t.get("noOfShareAcq") != null ? new BigDecimal(t.get("noOfShareAcq").toString()) : null,
                t.get("acqPriceTo") != null ? new BigDecimal(t.get("acqPriceTo").toString()) : null,
                parseDate((String) t.getOrDefault("date", ""))
            )).toList();

            return DataEnvelope.of(trades, name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[SEBIInsider] fetch failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }

    private LocalDate parseDate(String raw) {
        try { return LocalDate.parse(raw.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
