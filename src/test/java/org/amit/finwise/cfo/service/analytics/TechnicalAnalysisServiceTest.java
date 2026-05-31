package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
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
    StockPriceHistoryRepository priceRepo;

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
        when(priceRepo.findRecentBySymbol(eq("TEST"), any(LocalDate.class)))
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

        when(priceRepo.findRecentBySymbol(eq("TEST"), any(LocalDate.class)))
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

        when(priceRepo.findRecentBySymbol(eq("TEST"), any(LocalDate.class)))
                .thenReturn(priceHistory(closes, highs, lows));

        TechnicalSnapshot snap = service.analyze("TEST").orElseThrow();
        assertEquals(2.0, snap.atr14(), 1e-9, "ATR of a constant true range equals that range");
    }

    @Test
    void analyze_returnsEmpty_whenInsufficientHistory() {
        when(priceRepo.findRecentBySymbol(eq("TEST"), any(LocalDate.class)))
                .thenReturn(closesOnly(new double[]{100, 101, 102, 103, 104}));

        Optional<TechnicalSnapshot> snap = service.analyze("TEST");
        assertTrue(snap.isEmpty(), "fewer than 15 data points must yield no snapshot");
    }
}