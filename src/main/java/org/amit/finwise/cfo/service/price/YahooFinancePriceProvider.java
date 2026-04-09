package org.amit.finwise.cfo.service.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches daily OHLCV price history from Yahoo Finance (unofficial free API).
 *
 * Endpoint:
 *   https://query1.finance.yahoo.com/v8/finance/chart/{SYMBOL}.NS?range=Xd&interval=1d
 *
 * NSE stocks require the ".NS" suffix (e.g. HDFCBANK → HDFCBANK.NS).
 * BSE stocks use ".BO" — not used here since we target NSE.
 *
 * No API key required. Rate-limited informally; too many rapid requests get 429.
 * Fallback URL (query2) is tried automatically on 429 or connection failure.
 *
 * JSON response structure:
 * {
 *   "chart": {
 *     "result": [{
 *       "timestamp": [epoch_seconds, ...],
 *       "indicators": {
 *         "quote": [{ "open": [...], "high": [...], "low": [...], "close": [...], "volume": [...] }]
 *       }
 *     }],
 *     "error": null
 *   }
 * }
 */
@Slf4j
public class YahooFinancePriceProvider implements PriceDataProvider {

    private static final String PRIMARY_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}.NS?range={range}d&interval=1d";
    private static final String FALLBACK_URL =
            "https://query2.finance.yahoo.com/v8/finance/chart/{symbol}.NS?range={range}d&interval=1d";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public YahooFinancePriceProvider() {
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .defaultHeader("Accept", "application/json")
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String providerName() { return "yahoo-finance"; }

    @Override
    public boolean requiresApiKey() { return false; }

    @Override
    public List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException {
        // Try primary host first, then fallback host
        try {
            return fetch(PRIMARY_URL, symbol, days);
        } catch (PriceProviderException primaryEx) {
            log.debug("[Yahoo] Primary host failed for {}: {}, trying fallback host",
                    symbol, primaryEx.getMessage());
            try {
                return fetch(FALLBACK_URL, symbol, days);
            } catch (PriceProviderException fallbackEx) {
                throw new PriceProviderException(
                        "Both Yahoo Finance hosts failed for " + symbol + ": " + fallbackEx.getMessage(),
                        fallbackEx);
            }
        }
    }

    private List<DailyPrice> fetch(String urlTemplate, String symbol, int days)
            throws PriceProviderException {
        String url = urlTemplate
                .replace("{symbol}", symbol)
                .replace("{range}", String.valueOf(days));

        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new PriceProviderException("HTTP request failed: " + e.getMessage(), e);
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new PriceProviderException("Empty response from " + url);
        }

        return parseResponse(responseBody, symbol);
    }

    private List<DailyPrice> parseResponse(String json, String symbol)
            throws PriceProviderException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode chart = root.path("chart");

            // Check for API-level error
            JsonNode error = chart.path("error");
            if (!error.isNull() && error.has("code")) {
                throw new PriceProviderException("Yahoo Finance API error for " + symbol
                        + ": " + error.path("description").asText());
            }

            JsonNode result = chart.path("result");
            if (result.isEmpty() || result.get(0) == null) {
                throw new PriceProviderException("No data returned for symbol: " + symbol);
            }

            JsonNode first = result.get(0);
            JsonNode timestamps = first.path("timestamp");
            JsonNode quote = first.path("indicators").path("quote").get(0);

            if (timestamps.isEmpty() || quote == null) {
                throw new PriceProviderException("Empty price series for symbol: " + symbol);
            }

            JsonNode opens   = quote.path("open");
            JsonNode highs   = quote.path("high");
            JsonNode lows    = quote.path("low");
            JsonNode closes  = quote.path("close");
            JsonNode volumes = quote.path("volume");

            List<DailyPrice> prices = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode closeNode = closes.get(i);
                if (closeNode == null || closeNode.isNull()) continue; // market closed day

                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();

                prices.add(new DailyPrice(
                        date,
                        safeDecimal(opens.get(i)),
                        safeDecimal(highs.get(i)),
                        safeDecimal(lows.get(i)),
                        safeDecimal(closes.get(i)),
                        safeLong(volumes.get(i))
                ));
            }

            log.debug("[Yahoo] Fetched {} days of data for {}", prices.size(), symbol);
            return prices;

        } catch (PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceProviderException("Failed to parse Yahoo Finance response for "
                    + symbol + ": " + e.getMessage(), e);
        }
    }

    private BigDecimal safeDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return BigDecimal.valueOf(node.asDouble()).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private Long safeLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.asLong();
    }
}
