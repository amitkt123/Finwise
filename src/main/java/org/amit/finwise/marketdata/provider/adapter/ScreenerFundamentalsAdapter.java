package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenerFundamentalsAdapter implements MarketFeedProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "screener"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.FUNDAMENTALS; }
    @Override public boolean isHealthy() { return true; }

    /**
     * Fetches company fundamentals from Screener.in's JSON endpoint.
     * Returns raw map — callers extract what they need.
     * URL: https://www.screener.in/api/company/{symbol}/
     */
    public DataEnvelope<Map<?, ?>> fetchFundamentals(String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.screener.in/api/company/" + symbol + "/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(Map.class);
            return response != null
                ? DataEnvelope.of(response, name(), DataQuality.EOD)
                : DataEnvelope.missing(name(), "No data for " + symbol);
        } catch (Exception e) {
            log.warn("[Screener] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
