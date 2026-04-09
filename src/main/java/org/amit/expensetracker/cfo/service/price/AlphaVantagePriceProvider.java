package org.amit.expensetracker.cfo.service.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fetches daily OHLCV price history from Alpha Vantage.
 *
 * Free tier: 25 API requests/day, 5 requests/minute.
 * Requires an API key (free at https://www.alphavantage.co/support/#api-key).
 *
 * NSE stocks: append ".BSE" suffix for BSE or ".NSE" suffix for NSE
 * (Alpha Vantage uses ".NSE" for NSE India symbols).
 *
 * Endpoint:
 *   https://www.alphavantage.co/query
 *     ?function=TIME_SERIES_DAILY
 *     &symbol={SYMBOL}.NSE
 *     &outputsize=compact   (last 100 trading days)
 *     &apikey={KEY}
 *
 * JSON response:
 * {
 *   "Meta Data": { ... },
 *   "Time Series (Daily)": {
 *     "2024-01-15": {
 *       "1. open": "100.5",
 *       "2. high": "102.0",
 *       "3. low": "99.0",
 *       "4. close": "101.0",
 *       "5. volume": "1000000"
 *     },
 *     ...
 *   }
 * }
 */
@Slf4j
public class AlphaVantagePriceProvider implements PriceDataProvider {

    private static final String BASE_URL =
            "https://www.alphavantage.co/query" +
            "?function=TIME_SERIES_DAILY" +
            "&symbol={symbol}.NSE" +
            "&outputsize=compact" +
            "&apikey={apiKey}";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AlphaVantagePriceProvider(String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.builder()
                .defaultHeader("User-Agent", "expense-tracker/1.0")
                .build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String providerName() { return "alpha-vantage"; }

    @Override
    public boolean requiresApiKey() { return true; }

    @Override
    public List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException {
        String url = BASE_URL
                .replace("{symbol}", symbol)
                .replace("{apiKey}", apiKey);

        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new PriceProviderException("Alpha Vantage HTTP request failed: " + e.getMessage(), e);
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new PriceProviderException("Empty response from Alpha Vantage for " + symbol);
        }

        return parseResponse(responseBody, symbol, days);
    }

    private List<DailyPrice> parseResponse(String json, String symbol, int days)
            throws PriceProviderException {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Alpha Vantage returns rate-limit or error in a "Note" or "Information" field
            if (root.has("Note")) {
                throw new PriceProviderException("Alpha Vantage rate limit hit: "
                        + root.get("Note").asText());
            }
            if (root.has("Information")) {
                throw new PriceProviderException("Alpha Vantage API info: "
                        + root.get("Information").asText());
            }
            if (root.has("Error Message")) {
                throw new PriceProviderException("Alpha Vantage error for " + symbol
                        + ": " + root.get("Error Message").asText());
            }

            JsonNode timeSeries = root.path("Time Series (Daily)");
            if (timeSeries.isMissingNode() || timeSeries.isEmpty()) {
                throw new PriceProviderException("No time series data from Alpha Vantage for " + symbol);
            }

            LocalDate cutoff = LocalDate.now().minusDays(days);
            List<DailyPrice> prices = new ArrayList<>();

            Iterator<Map.Entry<String, JsonNode>> entries = timeSeries.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                LocalDate date = LocalDate.parse(entry.getKey(), DATE_FMT);
                if (date.isBefore(cutoff)) continue;

                JsonNode day = entry.getValue();
                prices.add(new DailyPrice(
                        date,
                        safeDecimal(day.get("1. open")),
                        safeDecimal(day.get("2. high")),
                        safeDecimal(day.get("3. low")),
                        safeDecimal(day.get("4. close")),
                        safeLong(day.get("5. volume"))
                ));
            }

            // Sort ascending by date
            prices.sort(java.util.Comparator.comparing(DailyPrice::date));
            log.debug("[AlphaVantage] Fetched {} days of data for {}", prices.size(), symbol);
            return prices;

        } catch (PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceProviderException("Failed to parse Alpha Vantage response for "
                    + symbol + ": " + e.getMessage(), e);
        }
    }

    private BigDecimal safeDecimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return new BigDecimal(node.asText().trim());
    }

    private Long safeLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.asLong();
    }
}
