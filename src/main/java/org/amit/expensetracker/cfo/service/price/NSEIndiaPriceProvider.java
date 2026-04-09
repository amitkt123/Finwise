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
import java.util.List;

/**
 * Fetches daily price history from NSE India's official website.
 *
 * No API key required, but NSE India enforces browser-like access:
 *   - A valid session must be established first (GET nseindia.com to get cookies)
 *   - The Cookie header from that session must be passed in subsequent requests
 *   - User-Agent must be a real browser string
 *
 * Endpoint (historical equity data):
 *   https://www.nseindia.com/api/historical/cm/equity
 *     ?symbol={SYMBOL}&series=["EQ"]&from={DD-MM-YYYY}&to={DD-MM-YYYY}
 *
 * Session establishment:
 *   Step 1: GET https://www.nseindia.com  → capture Set-Cookie response headers
 *   Step 2: Use captured cookies in all subsequent API calls
 *
 * JSON response (array inside "data" field):
 * {
 *   "data": [
 *     {
 *       "CH_SYMBOL": "HDFCBANK",
 *       "CH_SERIES": "EQ",
 *       "CH_TIMESTAMP": "15-Jan-2024",
 *       "CH_OPENING_PRICE": 1650.0,
 *       "CH_TRADE_HIGH_PRICE": 1680.0,
 *       "CH_TRADE_LOW_PRICE": 1640.0,
 *       "CH_CLOSING_PRICE": 1665.0,
 *       "CH_TOT_TRADED_QTY": 4500000,
 *       "HIGH_EQ": 1680.0,
 *       "LOW_EQ": 1640.0,
 *       "OPEN_EQ": 1650.0,
 *       "CLOSE_EQ": 1665.0
 *     },
 *     ...
 *   ]
 * }
 */
@Slf4j
public class NSEIndiaPriceProvider implements PriceDataProvider {

    private static final String NSE_HOME_URL    = "https://www.nseindia.com";
    private static final String NSE_HISTORY_URL =
            "https://www.nseindia.com/api/historical/cm/equity" +
            "?symbol={symbol}&series=[\"EQ\"]&from={from}&to={to}";

    private static final DateTimeFormatter NSE_DATE_FMT  = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter NSE_RESP_FMT  = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper;

    // Session cookies captured from GET nseindia.com — refreshed when expired
    private volatile String sessionCookies = null;
    private volatile java.time.LocalDateTime cookieExpiry = java.time.LocalDateTime.MIN;

    public NSEIndiaPriceProvider() {
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String providerName() { return "nse-india"; }

    @Override
    public boolean requiresApiKey() { return false; }

    @Override
    public List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException {
        ensureValidSession();

        LocalDate to   = LocalDate.now();
        LocalDate from = to.minusDays(days);

        String url = NSE_HISTORY_URL
                .replace("{symbol}", symbol)
                .replace("{from}", from.format(NSE_DATE_FMT))
                .replace("{to}",   to.format(NSE_DATE_FMT));

        RestClient client = buildSessionClient();
        String responseBody;
        try {
            responseBody = client.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            // Session may have expired — invalidate and retry once
            sessionCookies = null;
            throw new PriceProviderException("NSE India request failed (session may be expired): "
                    + e.getMessage(), e);
        }

        return parseResponse(responseBody, symbol);
    }

    /**
     * Establishes (or renews) an NSE India session by hitting the homepage.
     * Captures the Set-Cookie headers and caches them for 30 minutes.
     */
    private void ensureValidSession() throws PriceProviderException {
        if (sessionCookies != null
                && java.time.LocalDateTime.now().isBefore(cookieExpiry)) {
            return;
        }

        log.debug("[NSE] Establishing new session");
        try {
            // We need to capture raw response headers — use java.net.http for this
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NSE_HOME_URL))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Collect all Set-Cookie headers into a single Cookie header string
            List<String> cookies = response.headers().allValues("set-cookie");
            if (cookies.isEmpty()) {
                throw new PriceProviderException("NSE India did not return any session cookies");
            }

            // Extract cookie name=value pairs (strip path/domain/expires attributes)
            StringBuilder sb = new StringBuilder();
            for (String cookie : cookies) {
                String nameValue = cookie.split(";")[0].trim();
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(nameValue);
            }

            sessionCookies = sb.toString();
            cookieExpiry = java.time.LocalDateTime.now().plusMinutes(25); // NSE sessions ~30min
            log.debug("[NSE] Session established, {} cookies captured", cookies.size());

        } catch (PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceProviderException("Failed to establish NSE India session: "
                    + e.getMessage(), e);
        }
    }

    private RestClient buildSessionClient() {
        return RestClient.builder()
                .defaultHeader("User-Agent", BROWSER_UA)
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                .defaultHeader("Referer", NSE_HOME_URL)
                .defaultHeader("Cookie", sessionCookies != null ? sessionCookies : "")
                .build();
    }

    private List<DailyPrice> parseResponse(String json, String symbol)
            throws PriceProviderException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");

            if (data.isMissingNode() || !data.isArray()) {
                throw new PriceProviderException("No 'data' array in NSE India response for " + symbol);
            }

            List<DailyPrice> prices = new ArrayList<>();
            for (JsonNode row : data) {
                String dateStr = row.path("CH_TIMESTAMP").asText();
                if (dateStr.isBlank()) continue;

                LocalDate date = LocalDate.parse(dateStr, NSE_RESP_FMT);

                prices.add(new DailyPrice(
                        date,
                        safeDecimal(row, "CH_OPENING_PRICE"),
                        safeDecimal(row, "CH_TRADE_HIGH_PRICE"),
                        safeDecimal(row, "CH_TRADE_LOW_PRICE"),
                        safeDecimal(row, "CH_CLOSING_PRICE"),
                        row.path("CH_TOT_TRADED_QTY").isNull() ? null
                                : row.path("CH_TOT_TRADED_QTY").asLong()
                ));
            }

            // Sort ascending (NSE returns newest-first)
            prices.sort(java.util.Comparator.comparing(DailyPrice::date));
            log.debug("[NSE] Fetched {} days of data for {}", prices.size(), symbol);
            return prices;

        } catch (PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceProviderException("Failed to parse NSE India response for "
                    + symbol + ": " + e.getMessage(), e);
        }
    }

    private BigDecimal safeDecimal(JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (val.isNull() || val.isMissingNode()) return null;
        double d = val.asDouble();
        return d == 0 ? null : BigDecimal.valueOf(d).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
