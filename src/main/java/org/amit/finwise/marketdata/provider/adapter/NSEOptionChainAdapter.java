package org.amit.finwise.marketdata.provider.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Live option chain (equities) for a symbol. Routed through {@link NseApiClient},
 * which handles the cookie warm-up NSE requires — a bare RestClient call with just
 * a User-Agent header gets 401/403'd.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NSEOptionChainAdapter implements MarketFeedProvider {

    private final NseApiClient nseApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return "nse-option-chain"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.OPTION_CHAIN; }
    @Override public boolean isHealthy() { return true; }

    /** Returns raw option chain map for a symbol from NSE's free endpoint. */
    public DataEnvelope<Map<?, ?>> fetchOptionChain(String symbol) {
        try {
            Optional<String> body = nseApiClient.fetchOptionChain(symbol);
            if (body.isEmpty()) {
                return DataEnvelope.missing(name(), "NSE option chain unavailable for " + symbol);
            }

            Map<?, ?> response = objectMapper.readValue(body.get(), Map.class);
            return response != null
                ? DataEnvelope.of(response, name(), DataQuality.LIVE)
                : DataEnvelope.missing(name(), "Empty response for " + symbol);
        } catch (Exception e) {
            log.warn("[NSEOptionChain] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
