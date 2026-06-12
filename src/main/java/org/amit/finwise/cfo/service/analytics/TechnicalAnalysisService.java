package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.DataQualityFlag;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Computes technical indicators from StockPriceHistory.
 *
 * All price math uses adjustedClose (fallback closePrice) to avoid
 * corporate-action artifacts in moving averages and return series.
 * SUSPECT_GAP records are excluded from return computations (they are kept
 * in the raw price series for SMA/EMA/ATR context but won't distort returns).
 *
 * RSI and ATR both use Wilder's exponential smoothing, not naive period averages.
 * MACD uses standard EMAs (alpha = 2/(n+1)) on the adjusted-close series.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TechnicalAnalysisService {

    private final StockPriceHistoryRepository priceRepo;

    /** Number of calendar days to look back. 400 covers 252 trading days + weekends + holidays. */
    private static final int LOOKBACK_CALENDAR_DAYS = 400;

    private static final double SQRT_252 = Math.sqrt(252.0);

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the full technical snapshot for a symbol.
     * Returns empty if fewer than 15 valid data points exist (not enough for RSI).
     */
    public Optional<TechnicalSnapshot> analyze(String symbol) {
        LocalDate since = LocalDate.now().minusDays(LOOKBACK_CALENDAR_DAYS);
        List<StockPriceHistory> raw = priceRepo.findRecentBySymbol(symbol.toUpperCase(), since);
        if (raw.size() < 15) {
            log.debug("[TechnicalAnalysis] {} — only {} records, skipping", symbol, raw.size());
            return Optional.empty();
        }

        // Sort ascending by date; exclude SUSPECT_GAP for return-based calcs
        List<StockPriceHistory> sorted = raw.stream()
                .sorted(Comparator.comparing(StockPriceHistory::getPriceDate))
                .toList();

        int n = sorted.size();
        double[] closes = new double[n];
        double[] highs  = new double[n];
        double[] lows   = new double[n];
        long[] volumes  = new long[n];

        for (int i = 0; i < n; i++) {
            StockPriceHistory h = sorted.get(i);
            BigDecimal adj = h.getAdjustedClose() != null ? h.getAdjustedClose() : h.getClosePrice();
            closes[i] = adj != null ? adj.doubleValue() : Double.NaN;
            highs[i]  = h.getHighPrice()  != null ? h.getHighPrice().doubleValue()  : closes[i];
            lows[i]   = h.getLowPrice()   != null ? h.getLowPrice().doubleValue()   : closes[i];
            volumes[i] = h.getVolume() != null ? h.getVolume() : 0L;
        }

        LocalDate asOf     = sorted.getLast().getPriceDate();
        double lastClose   = closes[n - 1];
        boolean hasFullHistory = n >= 200;

        // ── SMAs ──────────────────────────────────────────────────────────────
        double sma20  = n >= 20  ? sma(closes, n - 20,  20)  : Double.NaN;
        double sma50  = n >= 50  ? sma(closes, n - 50,  50)  : Double.NaN;
        double sma200 = n >= 200 ? sma(closes, n - 200, 200) : Double.NaN;

        // ── EMAs ──────────────────────────────────────────────────────────────
        double ema20  = n >= 20  ? ema(closes, 20)  : Double.NaN;
        double ema50  = n >= 50  ? ema(closes, 50)  : Double.NaN;
        double ema200 = n >= 200 ? ema(closes, 200) : Double.NaN;

        // ── Price vs SMA % ────────────────────────────────────────────────────
        double pctVsSma20  = pct(lastClose, sma20);
        double pctVsSma50  = pct(lastClose, sma50);
        double pctVsSma200 = pct(lastClose, sma200);

        // ── RSI(14) Wilder ────────────────────────────────────────────────────
        double rsi14 = n >= 15 ? rsi(closes, 14) : Double.NaN;

        // ── ATR(14) Wilder ────────────────────────────────────────────────────
        double atr14 = n >= 15 ? atr(highs, lows, closes, 14) : Double.NaN;
        double atrPct = (!Double.isNaN(atr14) && lastClose > 0)
                ? (atr14 / lastClose) * 100 : Double.NaN;

        // ── 20-day realized volatility (annualized) ───────────────────────────
        double realizedVol20dAnn = n >= 21 ? realizedVolAnn(closes, n - 21, 20) : Double.NaN;

        // ── 52-week high / low ────────────────────────────────────────────────
        double high52w = high(closes, Math.max(0, n - 252), n);
        double low52w  = low(closes,  Math.max(0, n - 252), n);
        double pctFrom52wHigh = pct(lastClose, high52w);
        double pctFrom52wLow  = pct(lastClose, low52w);

        // ── Returns ───────────────────────────────────────────────────────────
        double return5d  = n >= 6  ? (lastClose / closes[n - 6])  - 1 : Double.NaN;
        double return20d = n >= 21 ? (lastClose / closes[n - 21]) - 1 : Double.NaN;

        // ── MACD(12,26,9) ─────────────────────────────────────────────────────
        double[] macdResult = n >= 35 ? macd(closes) : new double[]{Double.NaN, Double.NaN, Double.NaN};
        double macdLine     = macdResult[0];
        double macdSignal   = macdResult[1];
        double macdHist     = macdResult[2];

        // ── Golden/death cross (state and event) ───────────────────────────────
        boolean goldenCross = !Double.isNaN(sma50) && !Double.isNaN(sma200) && sma50 > sma200;

        TechnicalSnapshot.CrossEvent crossEvent = TechnicalSnapshot.CrossEvent.NONE;
        int daysSinceLastCross = -1;
        if (!Double.isNaN(sma50) && !Double.isNaN(sma200) && n >= 2) {
            // Check if there was a cross today vs yesterday
            double prevSma50 = n >= 51 ? sma(closes, n - 51, 50) : Double.NaN;
            double prevSma200 = n >= 201 ? sma(closes, n - 201, 200) : Double.NaN;
            if (!Double.isNaN(prevSma50) && !Double.isNaN(prevSma200)) {
                boolean wasPrevGolden = prevSma50 > prevSma200;
                if (goldenCross != wasPrevGolden) {
                    crossEvent = goldenCross ? TechnicalSnapshot.CrossEvent.GOLDEN_TODAY
                                             : TechnicalSnapshot.CrossEvent.DEATH_TODAY;
                }
            }

            // Count sessions since last cross (up to 120 session scan)
            daysSinceLastCross = daysSinceCross(closes, 120);
        }

        // ── Bollinger Bands (20, 2σ) ──────────────────────────────────────────
        double[] bollingerResult = n >= 20 ? bollingerBands(closes, n - 20, 20, 2.0)
                                            : new double[]{Double.NaN, Double.NaN, Double.NaN};
        double bollingerMid = bollingerResult[0];
        double bollingerUpper = bollingerResult[1];
        double bollingerLower = bollingerResult[2];
        double bollingerPctB = Double.NaN;
        double bollingerBandwidth = Double.NaN;
        if (!Double.isNaN(bollingerUpper) && !Double.isNaN(bollingerLower)) {
            double range = bollingerUpper - bollingerLower;
            if (range > 0) {
                bollingerPctB = (lastClose - bollingerLower) / range;
                bollingerBandwidth = range / bollingerMid;
            }
        }

        // ── Volume signals ────────────────────────────────────────────────────
        double obv = n >= 1 ? obv(closes, volumes, n) : Double.NaN;
        double vwap20d = n >= 20 ? vwap(closes, highs, lows, volumes, n - 20, 20) : Double.NaN;
        double volumeRatio = Double.NaN;
        if (n >= 21 && volumes[n - 1] > 0) {
            double avgVol20 = 0;
            for (int i = Math.max(0, n - 21); i < n - 1; i++) avgVol20 += volumes[i];
            avgVol20 /= Math.min(20, n - 1);
            if (avgVol20 > 0) volumeRatio = volumes[n - 1] / avgVol20;
        }

        // ── State labels ──────────────────────────────────────────────────────
        TechnicalSnapshot.Momentum momentum;
        if (!Double.isNaN(rsi14)) {
            momentum = rsi14 >= 70 ? TechnicalSnapshot.Momentum.OVERBOUGHT
                     : rsi14 <= 30 ? TechnicalSnapshot.Momentum.OVERSOLD
                     : TechnicalSnapshot.Momentum.NEUTRAL;
        } else {
            momentum = TechnicalSnapshot.Momentum.NEUTRAL;
        }

        TechnicalSnapshot.Trend trend;
        boolean aboveSma50  = !Double.isNaN(sma50)  && lastClose > sma50;
        boolean aboveSma200 = !Double.isNaN(sma200) && lastClose > sma200;
        boolean recentUp    = !Double.isNaN(return20d) && return20d > 0;
        if (aboveSma50 && recentUp) {
            trend = TechnicalSnapshot.Trend.UP;
        } else if (!aboveSma50 && !aboveSma200 && !recentUp) {
            trend = TechnicalSnapshot.Trend.DOWN;
        } else {
            trend = TechnicalSnapshot.Trend.SIDEWAYS;
        }

        log.debug("[TechnicalAnalysis] {} — RSI={}, Trend={}, Momentum={}, dataPoints={}",
                symbol, String.format("%.1f", rsi14), trend, momentum, n);

        return Optional.of(new TechnicalSnapshot(
                symbol, asOf, lastClose,
                sma20, sma50, sma200,
                ema20, ema50, ema200,
                pctVsSma20, pctVsSma50, pctVsSma200,
                rsi14,
                atr14, atrPct, realizedVol20dAnn,
                high52w, low52w, pctFrom52wHigh, pctFrom52wLow,
                returnPct(return5d), returnPct(return20d),
                goldenCross, crossEvent, daysSinceLastCross,
                macdLine, macdSignal, macdHist,
                bollingerPctB, bollingerBandwidth,
                obv, vwap20d, volumeRatio,
                trend, momentum,
                hasFullHistory, n
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indicator implementations
    // ─────────────────────────────────────────────────────────────────────────

    /** Simple moving average of closes[from..from+period). */
    private static double sma(double[] closes, int from, int period) {
        double sum = 0;
        for (int i = from; i < from + period; i++) sum += closes[i];
        return sum / period;
    }

    /**
     * Exponential moving average over the last {@code period} elements of closes.
     * Seeded with the SMA of the first {@code period} elements then rolled forward.
     * alpha = 2 / (period + 1).
     */
    private static double ema(double[] closes, int period) {
        double alpha = 2.0 / (period + 1);
        double e = sma(closes, 0, period);
        for (int i = period; i < closes.length; i++) {
            e = alpha * closes[i] + (1 - alpha) * e;
        }
        return e;
    }

    /**
     * Wilder RSI over the full {@code closes} array using period {@code p}.
     * First RS = simple avg of first p gains / avg of first p losses.
     * Then Wilder smoothing: avgGain = (prev * (p-1) + curr) / p.
     */
    private static double rsi(double[] closes, int p) {
        // Seed
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= p; i++) {
            double diff = closes[i] - closes[i - 1];
            if (diff > 0) avgGain += diff;
            else          avgLoss -= diff;
        }
        avgGain /= p;
        avgLoss /= p;

        // Wilder smoothing for remaining bars
        for (int i = p + 1; i < closes.length; i++) {
            double diff = closes[i] - closes[i - 1];
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            avgGain = (avgGain * (p - 1) + gain) / p;
            avgLoss = (avgLoss * (p - 1) + loss) / p;
        }

        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    /**
     * Wilder ATR over the full OHLC arrays using period {@code p}.
     * TrueRange = max(H-L, |H-prevC|, |L-prevC|).
     * Seeded with simple avg of first p TrueRanges.
     */
    private static double atr(double[] highs, double[] lows, double[] closes, int p) {
        int n = closes.length;
        // Seed: avg of first p TRs (starting at index 1 since we need prevClose)
        double atrVal = 0;
        for (int i = 1; i <= p; i++) {
            atrVal += trueRange(highs[i], lows[i], closes[i - 1]);
        }
        atrVal /= p;

        // Wilder smoothing
        for (int i = p + 1; i < n; i++) {
            atrVal = (atrVal * (p - 1) + trueRange(highs[i], lows[i], closes[i - 1])) / p;
        }
        return atrVal;
    }

    private static double trueRange(double high, double low, double prevClose) {
        return Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }

    /**
     * 20-day realized volatility (annualized) from simple returns of
     * closes[from..from+period] — same return definition as ReturnSeriesService,
     * so this figure is directly comparable with the risk engine's volatility.
     */
    private static double realizedVolAnn(double[] closes, int from, int period) {
        double[] ret = new double[period];
        for (int i = 0; i < period; i++) {
            ret[i] = closes[from + i + 1] / closes[from + i] - 1.0;
        }
        double mean = 0;
        for (double r : ret) mean += r;
        mean /= period;
        double variance = 0;
        for (double r : ret) variance += (r - mean) * (r - mean);
        variance /= (period - 1);
        return Math.sqrt(variance) * SQRT_252 * 100; // percent
    }

    private static double high(double[] closes, int from, int to) {
        double h = closes[from];
        for (int i = from + 1; i < to; i++) if (closes[i] > h) h = closes[i];
        return h;
    }

    private static double low(double[] closes, int from, int to) {
        double l = closes[from];
        for (int i = from + 1; i < to; i++) if (closes[i] < l) l = closes[i];
        return l;
    }

    /**
     * MACD(12,26,9) using rolling EMAs computed bar-by-bar.
     *
     * Returns double[3]: {macd_line, signal_line, histogram} at the last bar.
     * Requires at least 35 data points (26 seed + 9 signal seed).
     */
    private static double[] macd(double[] closes) {
        int n = closes.length;
        double alpha12 = 2.0 / 13;
        double alpha26 = 2.0 / 27;

        // Seed EMAs from the first 26 bars
        double ema12 = sma(closes, 0, 12);
        double ema26 = sma(closes, 0, 26);

        // Roll EMAs from bar 12 and 26 respectively
        for (int i = 12; i < 26; i++) ema12 = alpha12 * closes[i] + (1 - alpha12) * ema12;
        // ema26 seeded from bar 0..25, now advance ema12 to bar 25
        // Actually let's roll both from the same starting point, re-seeding properly
        // Reset: seed ema12 = SMA(closes[0..11]), then roll from 12
        ema12 = sma(closes, 0, 12);
        for (int i = 12; i < 26; i++) ema12 = alpha12 * closes[i] + (1 - alpha12) * ema12;

        // Now both at index 25. Collect MACD values from bar 26 onward
        int signalWindow = 9;
        double[] macdLine = new double[n - 26];

        for (int i = 26; i < n; i++) {
            ema12 = alpha12 * closes[i] + (1 - alpha12) * ema12;
            ema26 = alpha26 * closes[i] + (1 - alpha26) * ema26;
            macdLine[i - 26] = ema12 - ema26;
        }

        if (macdLine.length < signalWindow) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN};
        }

        // Signal = EMA(9) of MACD line
        double alpha9 = 2.0 / 10;
        double signal = sma(macdLine, 0, signalWindow);
        for (int i = signalWindow; i < macdLine.length; i++) {
            signal = alpha9 * macdLine[i] + (1 - alpha9) * signal;
        }

        double macdVal = macdLine[macdLine.length - 1];
        return new double[]{macdVal, signal, macdVal - signal};
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** (value / base - 1) * 100, returns NaN when base is NaN or zero. */
    private static double pct(double value, double base) {
        if (Double.isNaN(base) || base == 0) return Double.NaN;
        return (value / base - 1) * 100;
    }

    /** Convert fractional return to percent; propagates NaN. */
    private static double returnPct(double r) {
        return Double.isNaN(r) ? Double.NaN : r * 100;
    }

    /**
     * Bollinger Bands: mid = SMA, upper = mid + 2σ, lower = mid - 2σ (population std dev).
     * Returns {mid, upper, lower} at the specified window.
     */
    private static double[] bollingerBands(double[] closes, int from, int period, double stdDevs) {
        double mid = sma(closes, from, period);
        double variance = 0;
        for (int i = from; i < from + period; i++) {
            variance += (closes[i] - mid) * (closes[i] - mid);
        }
        variance /= period;
        double stdDev = Math.sqrt(variance);
        return new double[]{mid, mid + stdDevs * stdDev, mid - stdDevs * stdDev};
    }

    /**
     * On-Balance Volume: cumulative sum where +volume on up days, -volume on down days.
     * Computed over the full range.
     */
    private static double obv(double[] closes, long[] volumes, int n) {
        double obvVal = 0;
        for (int i = 1; i < n; i++) {
            if (closes[i] > closes[i - 1]) {
                obvVal += volumes[i];
            } else if (closes[i] < closes[i - 1]) {
                obvVal -= volumes[i];
            }
        }
        return obvVal;
    }

    /**
     * VWAP: volume-weighted average price using typical price = (H+L+C)/3.
     * Returns the VWAP at the end of the specified window.
     */
    private static double vwap(double[] closes, double[] highs, double[] lows, long[] volumes,
                                int from, int period) {
        double numerator = 0;
        long denominator = 0;
        for (int i = from; i < from + period; i++) {
            double typical = (highs[i] + lows[i] + closes[i]) / 3.0;
            numerator += typical * volumes[i];
            denominator += volumes[i];
        }
        return denominator > 0 ? numerator / denominator : Double.NaN;
    }

    /**
     * Trading sessions since the last SMA50/SMA200 cross — 0 means the cross
     * happened on the latest bar. Scans up to {@code maxLookback} sessions
     * backward for the first bar whose alignment differs from today's; the
     * cross bar is the one after it. Returns -1 if no cross is found in the
     * window (or history is too short to compute both SMAs).
     */
    private static int daysSinceCross(double[] closes, int maxLookback) {
        int n = closes.length;
        if (n < 201) return -1;

        boolean todayGolden = sma(closes, n - 50, 50) > sma(closes, n - 200, 200);

        // prevIdx needs indices [prevIdx-199, prevIdx] for SMA200, so prevIdx >= 199
        for (int daysBack = 1; daysBack <= Math.min(maxLookback, n - 200); daysBack++) {
            int prevIdx = n - 1 - daysBack;
            double prevSma50 = sma(closes, prevIdx - 49, 50);
            double prevSma200 = sma(closes, prevIdx - 199, 200);
            boolean prevGolden = prevSma50 > prevSma200;
            if (prevGolden != todayGolden) {
                return daysBack - 1; // cross occurred on the bar after prevIdx
            }
        }
        return -1;
    }
}
