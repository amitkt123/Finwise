package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorldBankAdapter implements MarketFeedProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "world-bank"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.WORLD_BANK; }
    @Override public boolean isHealthy() { return true; }

    /** Fetches latest annual India GDP growth rate from World Bank API (free, no auth). */
    public DataEnvelope<BigDecimal> fetchIndiaGdpGrowth() {
        try {
            // World Bank API: https://api.worldbank.org/v2/country/IN/indicator/NY.GDP.MKTP.KD.ZG?format=json&mrv=1
            List<?> response = restClientBuilder.build()
                .get()
                .uri("https://api.worldbank.org/v2/country/IN/indicator/NY.GDP.MKTP.KD.ZG"
                    + "?format=json&mrv=1")
                .retrieve()
                .body(List.class);

            if (response == null || response.size() < 2) return DataEnvelope.missing(name(), "Empty response");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get(1);
            if (data == null || data.isEmpty()) return DataEnvelope.missing(name(), "No data");

            Object value = data.get(0).get("value");
            if (value == null) return DataEnvelope.missing(name(), "Null value");
            return DataEnvelope.of(new BigDecimal(value.toString()), name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[WorldBank] fetch failed: {}", e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
