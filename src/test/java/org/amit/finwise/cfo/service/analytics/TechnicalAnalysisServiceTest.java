package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Known-answer tests for the Wilder RSI and ATR implementations.
 *
 * The RSI fixture is the canonical worked example published by StockCharts
 * (also matching Wilder's "New Concepts in Technical Trading Systems"):
 * the first 14 changes seed a simple average, then Wilder smoothing rolls forward.
 */
@ExtendWith(MockitoExtension.class)
class TechnicalAnalysisServiceTest {

    @Mock
    BackbonePriceReader backbone;

    @InjectMocks
    TechnicalAnalysisService service;

    /** Canonical StockCharts/Wilder RSI(14) close series. */
    private static final double[] WILDER_CLOSES = {
            44.3389, 44.0902, 44.1497, 43.6124, 44.3278, 44.8264, 45.0955, 45.4245,
            45.8433, 46.0826, 45.8931, 46.0328, 45.6140, 46.2820, 46.2820, 46.0028,
            46.0328, 46.4116, 46.2222, 45.6439, 46.2122, 46.2521, 45.7137, 46.4515,
            45.7835, 45.3548, 44.0288, 44.1783, 44.2181, 44.5714, 43.4205, 42.6628, 43.1314
    };

    private List<StockPriceHistory> priceHistory(double[] closes, double[] highs, double[] lows) {
        List<StockPriceHistory> out = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < closes.length; i++) {
            out.add(StockPriceHistory.builder()
                    .symbol("TEST")
                    .priceDate(start.plusDays(i))
                    .adjustedClose(BigDecimal.valueOf(closes[i]))
                    .closePrice(BigDecimal.valueOf(closes[i]))
                    .highPrice(BigDecimal.valueOf(highs[i]))
                    .lowPrice(BigDecimal.valueOf(lows[i]))
                    .build());
        }
        return out;
    }

    private List<StockPriceHistory> closesOnly(double[] closes) {
        return priceHistory(closes, closes, closes);
    }

    @Test
    void rsi14_matchesWilderWorkedExample_finalBar() {
        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(WILDER_CLOSES));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();

        // StockCharts publishes the final RSI of this series as ~37.77.
        assertEquals(37.77, snap.rsi14(), 0.2, "Wilder RSI(14) final value");
        assertEquals(TechnicalSnapshot.Momentum.NEUTRAL, snap.momentum());
    }

    @Test
    void rsi14_matchesWilderWorkedExample_firstComputableBar() {
        // First 15 closes → exactly one RSI value (the simple-average seed), published as 70.53.
        double[] first15 = new double[15];
        System.arraycopy(WILDER_CLOSES, 0, first15, 0, 15);

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(first15));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertEquals(70.53, snap.rsi14(), 0.1, "Wilder RSI(14) seed value");
    }

    @Test
    void atr14_equalsConstantTrueRange() {
        // 20 flat bars: close=100, high=101, low=99 → TrueRange = 2 every bar.
        // Wilder smoothing of a constant returns that constant: ATR = 2 exactly.
        int n = 20;
        double[] closes = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i] = 100.0;
            highs[i] = 101.0;
            lows[i] = 99.0;
        }

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(priceHistory(closes, highs, lows));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertEquals(2.0, snap.atr14(), 1e-9, "ATR of a constant true range equals that range");
    }

    @Test
    void analyze_returnsEmpty_whenInsufficientHistory() {
        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(new double[]{100, 101, 102, 103, 104}));

        Optional<TechnicalSnapshot> snap = service.analyze("TEST");
        assertTrue(snap.isEmpty(), "fewer than 15 data points must yield no snapshot");
    }

    // ── Phase 5a goldens ─────────────────────────────────────────────────────

    /**
     * Bollinger(20, 2σ population) on closes 1..20:
     *   mid = 10.5; Σ(x−10.5)² = 665 → popVar = 33.25, σ = √33.25
     *   upper = 10.5 + 2σ, lower = 10.5 − 2σ, range = 4σ
     *   %B = (20 − lower)/range = (9.5 + 2σ)/(4σ); bandwidth = 4σ/10.5
     */
    @Test
    void bollinger_matchesHandComputedLinearRamp() {
        double[] closes = new double[20];
        for (int i = 0; i < 20; i++) closes[i] = i + 1;

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(closes));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();

        double sigma = Math.sqrt(33.25);
        assertEquals((9.5 + 2 * sigma) / (4 * sigma), snap.bollingerPctB(), 1e-12, "%B");
        assertEquals(4 * sigma / 10.5, snap.bollingerBandwidth(), 1e-12, "bandwidth");
    }

    /**
     * Canonical OBV worked example (Wikipedia): cumulative OBV ends at 72,100.
     * Ten flat warm-up bars (equal closes contribute zero) pad the series past
     * the 15-observation minimum without disturbing the sum.
     */
    @Test
    void obv_matchesCanonicalWorkedExample() {
        double[] exampleCloses = {10, 10.15, 10.17, 10.13, 10.11, 10.15, 10.20, 10.20, 10.22, 10.21};
        long[] exampleVolumes = {25200, 30000, 25600, 32000, 23000, 40000, 36000, 20500, 23000, 27500};

        List<StockPriceHistory> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 10; i++) {          // flat warm-up: close 10, any volume
            rows.add(bar(start.plusDays(i), 10, 99999));
        }
        for (int i = 0; i < 10; i++) {
            rows.add(bar(start.plusDays(10 + i), exampleCloses[i], exampleVolumes[i]));
        }
        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class))).thenReturn(rows);

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertEquals(72100.0, snap.obv(), 1e-9, "OBV canonical example final value");
    }

    /**
     * Engineered last-bar golden cross: 200 bars at 100 (SMA50 == SMA200, not
     * golden), then one bar at 110 → SMA50 = 100.2 > SMA200 = 100.05.
     * The flip happens on the latest bar: GOLDEN_TODAY, 0 sessions since.
     */
    @Test
    void crossEvent_firesOnEngineeredLastBarGoldenCross() {
        double[] closes = new double[201];
        for (int i = 0; i < 200; i++) closes[i] = 100;
        closes[200] = 110;

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(closes));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertTrue(snap.goldenCross(), "SMA50 must sit above SMA200 after the jump");
        assertEquals(TechnicalSnapshot.CrossEvent.GOLDEN_TODAY, snap.crossEvent());
        assertEquals(0, snap.daysSinceLastCross(), "cross on the latest bar = 0 sessions ago");
    }

    /** Same series one bar later: the cross is a day old — state, not event. */
    @Test
    void crossEvent_isNoneDayAfterCross_withDaysSinceTracking() {
        double[] closes = new double[202];
        for (int i = 0; i < 200; i++) closes[i] = 100;
        closes[200] = 110;
        closes[201] = 110;

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(closes));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertTrue(snap.goldenCross());
        assertEquals(TechnicalSnapshot.CrossEvent.NONE, snap.crossEvent());
        assertEquals(1, snap.daysSinceLastCross(), "cross happened one session ago");
    }

    /** volumeRatio = today's volume / average of the prior 20 sessions. */
    @Test
    void volumeRatio_isTodayVolumeOverPrior20Average() {
        List<StockPriceHistory> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 20; i++) rows.add(bar(start.plusDays(i), 100 + i, 1000));
        rows.add(bar(start.plusDays(20), 121, 1500));

        when(backbone.dailySeries(eq("TEST"), any(LocalDate.class))).thenReturn(rows);

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertEquals(1.5, snap.volumeRatio(), 1e-12, "1500 vs flat 1000 average");
    }

    private StockPriceHistory bar(LocalDate date, double close, long volume) {
        return StockPriceHistory.builder()
                .symbol("TEST")
                .priceDate(date)
                .adjustedClose(BigDecimal.valueOf(close))
                .closePrice(BigDecimal.valueOf(close))
                .highPrice(BigDecimal.valueOf(close))
                .lowPrice(BigDecimal.valueOf(close))
                .volume(volume)
                .build();
    }
}