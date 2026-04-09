package org.amit.finwise.cfo.service.price;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Strategy interface for fetching daily OHLCV price history for a stock symbol.
 *
 * Implementations (in fallback order):
 *   1. YahooFinancePriceProvider  — free, no API key, NSE suffix ".NS"
 *   2. AlphaVantagePriceProvider  — free tier 25 req/day, API key required
 *   3. NSEIndiaPriceProvider      — official NSE India API, no key but needs session cookies
 *
 * PriceProviderConfig builds an ordered list. StockPriceService iterates them in sequence,
 * falling back to the next provider on any failure.
 */
public interface PriceDataProvider {

    /**
     * Fetch daily OHLCV price history for a symbol.
     *
     * @param symbol NSE symbol (e.g. "HDFCBANK") — implementations add exchange suffix as needed
     * @param days   number of calendar days of history to fetch (e.g. 30)
     * @return list of DailyPrice ordered by date ascending; empty list if no data available
     * @throws PriceProviderException if the provider fails and the fallback chain should try next
     */
    List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException;

    /**
     * Provider identifier shown in logs (e.g. "yahoo-finance", "alpha-vantage", "nse-india").
     */
    String providerName();

    /**
     * True if this provider requires an API key to be configured.
     * Used by PriceProviderConfig to skip providers whose key is blank.
     */
    boolean requiresApiKey();

    // ── Data record ───────────────────────────────────────────────────────────

    record DailyPrice(
            LocalDate date,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume
    ) {}

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown to signal the fallback chain should try the next provider. */
    class PriceProviderException extends Exception {
        public PriceProviderException(String message) { super(message); }
        public PriceProviderException(String message, Throwable cause) { super(message, cause); }
    }
}
