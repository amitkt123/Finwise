package org.amit.finwise.cfo.service.price;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
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

    // {ticker} is the full Yahoo ticker (e.g. "HDFCBANK.NS" or "^NSEI")
    private static final String PRIMARY_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?range={range}d&interval=1d";
    private static final String FALLBACK_URL =
            "https://query2.finance.yahoo.com/v8/finance/chart/{ticker}?range={range}d&interval=1d";

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

    /**
     * Fetch fundamentals snapshot from Yahoo quoteSummary endpoint.
     * Returns a FundamentalsSnapshot with P/E, P/B, EV/EBITDA, margins, etc.
     *
     * Endpoint: https://query1.finance.yahoo.com/v10/finance/quoteSummary/{SYMBOL}
     *   ?modules=defaultKeyStatistics,financialData,summaryDetail,incomeStatementHistory
     */
    public FundamentalsSnapshot fetchFundamentals(String symbol) throws PriceProviderException {
        String yahooTicker = symbol.startsWith("^") ? symbol : symbol + ".NS";
        try {
            return fetchFundamentalsSafe(yahooTicker, symbol);
        } catch (PriceProviderException primaryEx) {
            log.debug("[Yahoo] Fundamentals fetch failed for {}: {}", symbol, primaryEx.getMessage());
            throw primaryEx;
        }
    }

    private FundamentalsSnapshot fetchFundamentalsSafe(String yahooTicker, String symbol)
            throws PriceProviderException {
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + yahooTicker
                + "?modules=defaultKeyStatistics,financialData,summaryDetail,incomeStatementHistory";

        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new PriceProviderException("HTTP request failed for fundamentals: " + e.getMessage(), e);
        }

        if (responseBody == null || responseBody.isBlank()) {
            throw new PriceProviderException("Empty response from quoteSummary for " + symbol);
        }

        return parseFundamentalsResponse(responseBody, symbol);
    }

    /**
     * Fetch quarterly financial statements from the same v10 quoteSummary call
     * (Phase 7): income statement, balance sheet, cash flow — quarterly variants —
     * plus the earnings module for actual quarterly EPS.
     *
     * Yahoo serves ~4 trailing quarters; persistence accumulates to 8+ over time.
     */
    public List<QuarterlySnapshot> fetchQuarterlyFundamentals(String symbol) throws PriceProviderException {
        String yahooTicker = symbol.startsWith("^") ? symbol : symbol + ".NS";
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + yahooTicker
                + "?modules=earnings,balanceSheetHistoryQuarterly,cashflowStatementHistoryQuarterly,incomeStatementHistoryQuarterly";

        String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new PriceProviderException("HTTP request failed for quarterly fundamentals: " + e.getMessage(), e);
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new PriceProviderException("Empty quarterly quoteSummary response for " + symbol);
        }
        return parseQuarterlyResponse(responseBody, symbol);
    }

    /** Package-private for fixture-based testing. */
    List<QuarterlySnapshot> parseQuarterlyResponse(String json, String symbol)
            throws PriceProviderException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("quoteSummary").path("result");
            if (result.isEmpty() || result.get(0) == null) {
                throw new PriceProviderException("No quarterly quoteSummary data for " + symbol);
            }
            JsonNode data = result.get(0);

            // Merge the three statement arrays by quarter end date
            java.util.Map<LocalDate, QuarterlySnapshot> byQuarter = new java.util.TreeMap<>();

            JsonNode incomeStmts = data.path("incomeStatementHistoryQuarterly").path("incomeStatementHistory");
            for (JsonNode stmt : incomeStmts) {
                LocalDate end = extractEndDate(stmt);
                if (end == null) continue;
                QuarterlySnapshot q = byQuarter.computeIfAbsent(end, QuarterlySnapshot::new);
                q.revenue = extractBigDecimal(stmt.path("totalRevenue"));
                q.netIncome = extractBigDecimal(stmt.path("netIncome"));
                q.grossProfit = extractBigDecimal(stmt.path("grossProfit"));
            }

            JsonNode balanceStmts = data.path("balanceSheetHistoryQuarterly").path("balanceSheetStatements");
            for (JsonNode stmt : balanceStmts) {
                LocalDate end = extractEndDate(stmt);
                if (end == null) continue;
                QuarterlySnapshot q = byQuarter.computeIfAbsent(end, QuarterlySnapshot::new);
                q.totalAssets = extractBigDecimal(stmt.path("totalAssets"));
                q.totalLiabilities = extractBigDecimal(stmt.path("totalLiab"));
            }

            JsonNode cashflowStmts = data.path("cashflowStatementHistoryQuarterly").path("cashflowStatements");
            for (JsonNode stmt : cashflowStmts) {
                LocalDate end = extractEndDate(stmt);
                if (end == null) continue;
                QuarterlySnapshot q = byQuarter.computeIfAbsent(end, QuarterlySnapshot::new);
                q.operatingCashFlow = extractBigDecimal(stmt.path("totalCashFromOperatingActivities"));
            }

            // Quarterly actual EPS from the earnings module. Its labels ("4Q2025")
            // don't carry exact end dates, so attach by recency: the chart's last
            // entry pairs with the newest statement quarter, and so on backwards.
            JsonNode epsQuarters = data.path("earnings").path("earningsChart").path("quarterly");
            if (epsQuarters.isArray() && epsQuarters.size() > 0 && !byQuarter.isEmpty()) {
                List<LocalDate> quarterEnds = new ArrayList<>(byQuarter.keySet()); // ascending
                int offset = quarterEnds.size() - epsQuarters.size();
                for (int i = 0; i < epsQuarters.size(); i++) {
                    int target = offset + i;
                    if (target < 0 || target >= quarterEnds.size()) continue;
                    BigDecimal eps = extractBigDecimal(epsQuarters.get(i).path("actual"));
                    if (eps != null) byQuarter.get(quarterEnds.get(target)).eps = eps;
                }
            }

            return new ArrayList<>(byQuarter.values());
        } catch (PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceProviderException(
                    "Failed to parse quarterly quoteSummary for " + symbol + ": " + e.getMessage(), e);
        }
    }

    private LocalDate extractEndDate(JsonNode stmt) {
        JsonNode endDate = stmt.path("endDate");
        if (endDate.isMissingNode() || endDate.isNull()) return null;
        long epoch = endDate.has("raw") ? endDate.path("raw").asLong(0) : endDate.asLong(0);
        if (epoch <= 0) return null;
        return Instant.ofEpochSecond(epoch).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
    }

    /** One merged quarter of statement data. Fields are null when Yahoo omits them. */
    public static class QuarterlySnapshot {
        public final LocalDate quarterEnd;
        public BigDecimal revenue;
        public BigDecimal netIncome;
        public BigDecimal grossProfit;
        public BigDecimal eps;
        public BigDecimal totalAssets;
        public BigDecimal totalLiabilities;
        public BigDecimal operatingCashFlow;

        QuarterlySnapshot(LocalDate quarterEnd) {
            this.quarterEnd = quarterEnd;
        }
    }

    private FundamentalsSnapshot parseFundamentalsResponse(String json, String symbol)
            throws PriceProviderException {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.path("quoteSummary").path("result");

            if (result.isEmpty() || result.get(0) == null) {
                throw new PriceProviderException("No quoteSummary data for " + symbol);
            }

            JsonNode data = result.get(0);
            FundamentalsSnapshot snapshot = new FundamentalsSnapshot();

            // defaultKeyStatistics
            JsonNode keyStats = data.path("defaultKeyStatistics");
            if (!keyStats.isMissingNode()) {
                snapshot.trailingPE = extractBigDecimal(keyStats.path("trailingPE"));
                snapshot.forwardPE = extractBigDecimal(keyStats.path("forwardPE"));
                snapshot.priceToBook = extractBigDecimal(keyStats.path("priceToBook"));
                snapshot.pegRatio = extractBigDecimal(keyStats.path("pegRatio"));
            }

            // financialData
            JsonNode finData = data.path("financialData");
            if (!finData.isMissingNode()) {
                snapshot.evToRevenue = extractBigDecimal(finData.path("enterpriseToRevenue"));
                snapshot.evToEbitda = extractBigDecimal(finData.path("enterpriseToEbitda"));
                snapshot.grossMargin = extractBigDecimal(finData.path("grossMargins"));
                snapshot.operatingMargin = extractBigDecimal(finData.path("operatingMargins"));
                snapshot.ebitdaMargin = extractBigDecimal(finData.path("ebitdaMargins"));
                snapshot.netProfitMargin = extractBigDecimal(finData.path("profitMargins"));
                snapshot.returnOnEquity = extractBigDecimal(finData.path("returnOnEquity"));
                snapshot.debtToEquity = extractBigDecimal(finData.path("debtToEquity"));
                snapshot.freeCashFlow = extractBigDecimal(finData.path("freeCashflow"));
            }

            // summaryDetail
            JsonNode summaryDetail = data.path("summaryDetail");
            if (!summaryDetail.isMissingNode()) {
                snapshot.dividendYield = extractBigDecimal(summaryDetail.path("dividendYield"));
            }

            // incomeStatementHistory (for revenue growth)
            JsonNode incomeHistory = data.path("incomeStatementHistory");
            if (!incomeHistory.isMissingNode() && incomeHistory.has("incomeStatementHistory")) {
                JsonNode history = incomeHistory.path("incomeStatementHistory");
                if (history.isArray() && history.size() >= 2) {
                    BigDecimal current = extractBigDecimal(history.get(0).path("totalRevenue"));
                    BigDecimal previous = extractBigDecimal(history.get(1).path("totalRevenue"));
                    if (current != null && previous != null && previous.compareTo(BigDecimal.ZERO) > 0) {
                        snapshot.revenueGrowth = current.divide(previous, 4, java.math.RoundingMode.HALF_UP)
                                .subtract(BigDecimal.ONE).multiply(new BigDecimal("100"));
                    }
                }
            }

            return snapshot;
        } catch (Exception e) {
            throw new PriceProviderException(
                    "Failed to parse quoteSummary response for " + symbol + ": " + e.getMessage(), e);
        }
    }

    private BigDecimal extractBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try {
            if (node.has("raw")) {
                return BigDecimal.valueOf(node.path("raw").asDouble()).setScale(4, java.math.RoundingMode.HALF_UP);
            } else {
                return BigDecimal.valueOf(node.asDouble()).setScale(4, java.math.RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException {
        // Indices (e.g. ^NSEI) use their symbol directly; NSE equities get ".NS" suffix
        String yahooTicker = symbol.startsWith("^") ? symbol : symbol + ".NS";
        try {
            return fetch(PRIMARY_URL, yahooTicker, symbol, days);
        } catch (PriceProviderException primaryEx) {
            log.debug("[Yahoo] Primary host failed for {}: {}, trying fallback host",
                    symbol, primaryEx.getMessage());
            try {
                return fetch(FALLBACK_URL, yahooTicker, symbol, days);
            } catch (PriceProviderException fallbackEx) {
                throw new PriceProviderException(
                        "Both Yahoo Finance hosts failed for " + symbol + ": " + fallbackEx.getMessage(),
                        fallbackEx);
            }
        }
    }

    private List<DailyPrice> fetch(String urlTemplate, String yahooTicker, String symbol, int days)
            throws PriceProviderException {
        String url = urlTemplate
                .replace("{ticker}", yahooTicker)
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

            // Yahoo returns adjclose under indicators.adjclose[0].adjclose (may be absent)
            JsonNode adjCloseArray = null;
            JsonNode adjCloseContainer = first.path("indicators").path("adjclose");
            if (!adjCloseContainer.isMissingNode() && adjCloseContainer.isArray()
                    && adjCloseContainer.size() > 0) {
                adjCloseArray = adjCloseContainer.get(0).path("adjclose");
                if (adjCloseArray.isMissingNode()) adjCloseArray = null;
            }

            List<DailyPrice> prices = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode closeNode = closes.get(i);
                if (closeNode == null || closeNode.isNull()) continue; // market closed day

                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toLocalDate();

                BigDecimal adjClose = adjCloseArray != null
                        ? safeDecimal(adjCloseArray.get(i)) : null;

                prices.add(new DailyPrice(
                        date,
                        safeDecimal(opens.get(i)),
                        safeDecimal(highs.get(i)),
                        safeDecimal(lows.get(i)),
                        safeDecimal(closes.get(i)),
                        safeLong(volumes.get(i)),
                        adjClose
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
        return BigDecimal.valueOf(node.asDouble()).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private Long safeLong(JsonNode node) {
        if (node == null || node.isNull()) return null;
        return node.asLong();
    }

    /**
     * Snapshot of fundamental metrics fetched from Yahoo quoteSummary.
     * All fields are nullable; absence is captured as a data gap in the service layer.
     */
    @Data
    public static class FundamentalsSnapshot {
        public BigDecimal trailingPE;
        public BigDecimal forwardPE;
        public BigDecimal priceToBook;
        public BigDecimal pegRatio;
        public BigDecimal evToRevenue;
        public BigDecimal evToEbitda;
        public BigDecimal dividendYield;
        public BigDecimal revenueGrowth;
        public BigDecimal grossMargin;
        public BigDecimal ebitdaMargin;
        public BigDecimal operatingMargin;
        public BigDecimal netProfitMargin;
        public BigDecimal returnOnEquity;
        public BigDecimal debtToEquity;
        public BigDecimal freeCashFlow;
    }
}
