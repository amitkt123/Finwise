package org.amit.finwise.cfo.model;

import java.time.LocalDate;

/**
 * Immutable snapshot of all computed technical indicators for one symbol.
 *
 * All EMAs and SMAs use adjusted-close prices so corporate actions don't
 * create artificial crossovers. RSI and ATR use Wilder's smoothing (not
 * naive averages). NaN signals insufficient data for that indicator.
 */
public record TechnicalSnapshot(

        String symbol,
        LocalDate asOf,
        double lastClose,

        // ── Moving averages (adjusted close) ────────────────────────────────────
        double sma20,
        double sma50,
        double sma200,

        double ema20,
        double ema50,
        double ema200,

        // ── Price vs moving average (%above = positive) ─────────────────────────
        double pctVsSma20,
        double pctVsSma50,
        double pctVsSma200,

        // ── Momentum (Wilder RSI-14) ─────────────────────────────────────────────
        double rsi14,

        // ── Volatility / range ───────────────────────────────────────────────────
        double atr14,               // Wilder ATR(14) in price units
        double atrPct,              // ATR / close — normalized range
        double realizedVol20dAnn,   // 20-day realized vol, annualized (×√252)

        // ── 52-week levels ───────────────────────────────────────────────────────
        double high52w,
        double low52w,
        double pctFrom52wHigh,      // negative = below high (e.g. -12.3 means 12.3% below)
        double pctFrom52wLow,       // positive = above low

        // ── Short-term returns ───────────────────────────────────────────────────
        double return5d,            // (close[T] / close[T-5]) - 1, percent
        double return20d,           // (close[T] / close[T-20]) - 1, percent

        // ── Trend cross state ────────────────────────────────────────────────────
        boolean goldenCross,        // SMA50 > SMA200; NaN SMA200 → false

        // ── MACD (12,26,9) ───────────────────────────────────────────────────────
        double macd,                // EMA12 - EMA26
        double macdSignal,          // EMA9 of MACD series
        double macdHistogram,       // MACD - Signal

        // ── State labels ─────────────────────────────────────────────────────────
        Trend trend,
        Momentum momentum,

        // ── Data coverage ────────────────────────────────────────────────────────
        boolean hasFullHistory,     // true if ≥200 trading-day observations
        int dataPoints

) {
    public enum Trend    { UP, DOWN, SIDEWAYS }
    public enum Momentum { OVERBOUGHT, NEUTRAL, OVERSOLD }

    /** Render a labeled context block for LLM injection. */
    public String toContextBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("### Technicals: ").append(symbol).append(" (as of ").append(asOf).append(")\n");

        if (!hasFullHistory) {
            sb.append("⚠ PARTIAL_HISTORY: only ").append(dataPoints)
              .append(" trading days — SMA200/EMA200 unavailable\n");
        }

        sb.append(String.format("Close: ₹%.2f  |  Trend: %s  |  Momentum: %s%n",
                lastClose, trend, momentum));

        // RSI
        if (!Double.isNaN(rsi14)) {
            String rsiLabel = rsi14 >= 70 ? "OVERBOUGHT" : rsi14 <= 30 ? "OVERSOLD" : "NEUTRAL";
            sb.append(String.format("RSI(14): %.1f → %s%n", rsi14, rsiLabel));
        }

        // SMAs
        if (!Double.isNaN(sma20))
            sb.append(String.format("SMA20: ₹%.2f  (price %+.1f%%)%n", sma20, pctVsSma20));
        if (!Double.isNaN(sma50))
            sb.append(String.format("SMA50: ₹%.2f  (price %+.1f%%)%n", sma50, pctVsSma50));
        if (!Double.isNaN(sma200))
            sb.append(String.format("SMA200: ₹%.2f  (price %+.1f%%)  |  %s%n",
                    sma200, pctVsSma200, goldenCross ? "GOLDEN CROSS (50>200)" : "DEATH CROSS (50<200)"));

        // ATR / volatility
        if (!Double.isNaN(atr14))
            sb.append(String.format("ATR(14): ₹%.2f (%.1f%% of price)  |  Realized Vol 20d: %.1f%% ann.%n",
                    atr14, atrPct, realizedVol20dAnn));

        // 52-week
        sb.append(String.format("52w High: ₹%.2f (%+.1f%%)  |  52w Low: ₹%.2f (%+.1f%%)%n",
                high52w, pctFrom52wHigh, low52w, pctFrom52wLow));

        // Returns
        if (!Double.isNaN(return5d))
            sb.append(String.format("5d return: %+.1f%%  |  20d return: %+.1f%%  → trend %s%n",
                    return5d, return20d, return20d > 0 ? "UP" : "DOWN"));

        // MACD
        if (!Double.isNaN(macd))
            sb.append(String.format("MACD(12,26,9): %.3f  Signal: %.3f  Hist: %+.3f  → %s%n",
                    macd, macdSignal, macdHistogram,
                    macdHistogram > 0 ? "BULLISH" : "BEARISH"));

        return sb.toString();
    }
}
