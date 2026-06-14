package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.DataQualityFlag;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Verifies the single canonical return definition:
 *   r_t = P_t / P_{t-1} - 1, using adjustedClose (fallback closePrice),
 *   excluding SUSPECT_GAP points, and dropping symbols below MIN_OBSERVATIONS.
 */
@ExtendWith(MockitoExtension.class)
class ReturnSeriesServiceTest {

    @Mock
    BackbonePriceReader backbone;

    @InjectMocks
    ReturnSeriesService service;

    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    /**
     * Builds {@code count} records for a symbol where adjustedClose = 100 + i and
     * closePrice is a flat sentinel (999) — so a return computed off closePrice would be 0,
     * and any non-zero return proves adjustedClose was used.
     */
    private List<StockPriceHistory> ramp(String symbol, int count) {
        List<StockPriceHistory> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(StockPriceHistory.builder()
                    .symbol(symbol)
                    .priceDate(START.plusDays(i))
                    .adjustedClose(BigDecimal.valueOf(100 + i))
                    .closePrice(BigDecimal.valueOf(999))
                    .build());
        }
        return out;
    }

    @Test
    void computesReturns_fromAdjustedClose_andIncludesSymbolAboveMinObs() {
        // 62 records → 61 returns ≥ MIN_OBSERVATIONS (60) → symbol included.
        when(backbone.dailySeries(anyList(), any())).thenReturn(Map.of("AAA", ramp("AAA", 62)));

        Map<String, NavigableMap<LocalDate, Double>> result =
                service.getReturnSeries(List.of("AAA"), START);

        assertTrue(result.containsKey("AAA"));
        NavigableMap<LocalDate, Double> returns = result.get("AAA");
        assertEquals(61, returns.size());

        // First return: 101/100 - 1 = 0.01 (proves adjustedClose, not flat closePrice).
        double firstReturn = returns.get(START.plusDays(1));
        assertEquals(0.01, firstReturn, 1e-12);
        assertFalse(firstReturn == 0.0, "flat closePrice would give 0 — adjustedClose must be used");
    }

    @Test
    void excludesSuspectGapDates_butKeepsSymbol() {
        // 62 records, one flagged SUSPECT_GAP → its return date is dropped (60 returns remain ≥ 60).
        List<StockPriceHistory> records = ramp("AAA", 62);
        records.get(30).setDataQualityFlag(DataQualityFlag.SUSPECT_GAP);
        when(backbone.dailySeries(anyList(), any())).thenReturn(Map.of("AAA", records));

        Map<String, NavigableMap<LocalDate, Double>> result =
                service.getReturnSeries(List.of("AAA"), START);

        assertTrue(result.containsKey("AAA"));
        NavigableMap<LocalDate, Double> returns = result.get("AAA");
        assertEquals(60, returns.size(), "the SUSPECT_GAP point must be excluded");
        assertFalse(returns.containsKey(START.plusDays(30)),
                "no return should be recorded on the SUSPECT_GAP date");
    }

    @Test
    void dropsSymbol_belowMinObservations() {
        // Only 5 records → 4 returns < 60 → symbol omitted entirely.
        when(backbone.dailySeries(anyList(), any())).thenReturn(Map.of("BBB", ramp("BBB", 5)));

        Map<String, NavigableMap<LocalDate, Double>> result =
                service.getReturnSeries(List.of("BBB"), START);

        assertFalse(result.containsKey("BBB"),
                "INSUFFICIENT_HISTORY symbols must not appear in the return series");
    }
}