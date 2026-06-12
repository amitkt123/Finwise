package org.amit.finwise.cfo.service.macro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.price.NseSessionManager;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Fetches FII/DII (Foreign / Domestic Institutional Investor) net flows from
 * NSE India's API (published post-market, ~18:30–19:00 IST).
 *
 * Endpoint: https://www.nseindia.com/api/fiidiiTradeReact
 * Response is a top-level JSON array, one row per investor category:
 * <pre>
 * [
 *   {"category":"DII **","date":"12-Jun-2026","buyValue":"15,000.50",
 *    "sellValue":"13,000.25","netValue":"2,000.25"},
 *   {"category":"FII/FPI *","date":"12-Jun-2026", ... "netValue":"-1,234.56"}
 * ]
 * </pre>
 * Values are ₹ Cr, comma-grouped strings. Parsing is strict (parse-or-fail):
 * both FII and DII rows must be present and numeric, otherwise the fetch
 * counts as a failure. Consumers must tolerate a null result.
 *
 * Circuit breaker: after 3 consecutive failures, fetching pauses for 24 hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FiiDiiFlowProvider {

    private static final String FII_DII_URL = "https://www.nseindia.com/api/fiidiiTradeReact";
    private static final DateTimeFormatter NSE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private static final int MAX_FAILURES = 3;
    private static final long CIRCUIT_OPEN_MS = 24 * 60 * 60 * 1000L;

    private final NseSessionManager sessionManager;
    private final ObjectMapper objectMapper;

    private volatile int consecutiveFailures = 0;
    private volatile long circuitOpenUntil = 0;

    /**
     * Fetches the latest published FII/DII flows (₹ Cr, negative = net selling).
     * Returns null when the endpoint is down, the circuit breaker is open, or
     * the response doesn't parse — never throws.
     */
    public FiiDiiFlow fetchLatestFlow() {
        if (consecutiveFailures >= MAX_FAILURES && System.currentTimeMillis() < circuitOpenUntil) {
            log.warn("[FII/DII] Circuit breaker open, skipping fetch");
            return null;
        }

        try {
            String sessionCookies = sessionManager.getSessionCookies();
            RestClient client = RestClient.builder()
                    .defaultHeader("User-Agent", BROWSER_UA)
                    .defaultHeader("Accept", "application/json")
                    .defaultHeader("Referer", "https://www.nseindia.com")
                    .defaultHeader("Cookie", sessionCookies)
                    .build();

            String response = client.get()
                    .uri(FII_DII_URL)
                    .retrieve()
                    .body(String.class);

            if (response == null || response.isBlank()) {
                throw new IllegalStateException("empty response");
            }

            FiiDiiFlow flow = parseResponse(response);
            consecutiveFailures = 0;
            log.info("[FII/DII] {} — FII net ₹{} Cr, DII net ₹{} Cr",
                    flow.asOf(), flow.fiiNetFlowCr(), flow.diiNetFlowCr());
            return flow;

        } catch (Exception e) {
            handleFailure(e.getMessage());
            sessionManager.invalidateSession();
            return null;
        }
    }

    private void handleFailure(String message) {
        consecutiveFailures++;
        log.warn("[FII/DII] Fetch failed: {} (consecutive failures: {})", message, consecutiveFailures);
        if (consecutiveFailures >= MAX_FAILURES) {
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_OPEN_MS;
            log.error("[FII/DII] Circuit breaker opened for 24h after {} failures", consecutiveFailures);
        }
    }

    /**
     * Strict parser for the fiidiiTradeReact array. Package-private for testing.
     * Throws when either category row is missing or non-numeric.
     */
    FiiDiiFlow parseResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("expected non-empty top-level array");
        }

        BigDecimal fiiNet = null;
        BigDecimal diiNet = null;
        LocalDate asOf = null;

        for (JsonNode row : root) {
            String category = row.path("category").asText("").toUpperCase(Locale.ENGLISH);
            BigDecimal net = parseCroreValue(row.path("netValue").asText(null));
            if (category.contains("FII") || category.contains("FPI")) {
                fiiNet = net;
            } else if (category.contains("DII")) {
                diiNet = net;
            } else {
                continue;
            }
            String dateStr = row.path("date").asText(null);
            if (asOf == null && dateStr != null && !dateStr.isBlank()) {
                asOf = LocalDate.parse(dateStr, NSE_DATE_FMT);
            }
        }

        if (fiiNet == null || diiNet == null || asOf == null) {
            throw new IllegalArgumentException(
                    "incomplete FII/DII rows (fii=" + fiiNet + ", dii=" + diiNet + ", date=" + asOf + ")");
        }
        return new FiiDiiFlow(fiiNet, diiNet, asOf);
    }

    /** Parses NSE's comma-grouped ₹ Cr strings, e.g. "-12,345.67". */
    private static BigDecimal parseCroreValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return new BigDecimal(raw.replace(",", "").trim());
    }

    public record FiiDiiFlow(
            BigDecimal fiiNetFlowCr,  // FII/FPI net flow (₹ Cr, negative = net selling)
            BigDecimal diiNetFlowCr,  // DII net flow (₹ Cr, negative = net selling)
            LocalDate asOf
    ) {}
}
