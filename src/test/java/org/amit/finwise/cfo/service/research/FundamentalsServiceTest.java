package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.repository.StockFundamentalsRepository;
import org.amit.finwise.cfo.service.price.YahooFinancePriceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Known-answer test for the valuation z-score.
 *
 * Fixture: a 2-year P/E history of 30×15 and 30×25 → mean 20,
 * sample stdev (N−1) = √(60·25/59) ≈ 5.0422.
 * Current P/E of 30 ⇒ z = (30 − 20) / 5.0422 ≈ 1.98 ⇒ label EXPENSIVE (z > 1).
 */
@ExtendWith(MockitoExtension.class)
class FundamentalsServiceTest {

    @Mock
    StockFundamentalsRepository fundamentalsRepo;

    @Mock
    YahooFinancePriceProvider yahooProvider;

    @InjectMocks
    FundamentalsService service;

    @Test
    void valuationZScore_andLabel_matchHandComputedValues() throws Exception {
        // 60-point P/E history with mean 20, sample (N−1) stdev ≈ 5.0422.
        List<StockFundamentals> history = new ArrayList<>();
        LocalDate base = LocalDate.now().minusDays(400);
        for (int i = 0; i < 60; i++) {
            double pe = (i % 2 == 0) ? 15.0 : 25.0;
            history.add(StockFundamentals.builder()
                    .symbol("TEST")
                    .snapshotDate(base.plusDays(i))
                    .trailingPE(BigDecimal.valueOf(pe))
                    .build());
        }

        YahooFinancePriceProvider.FundamentalsSnapshot snap =
                new YahooFinancePriceProvider.FundamentalsSnapshot();
        snap.setTrailingPE(BigDecimal.valueOf(30));      // current multiple
        snap.setNetProfitMargin(BigDecimal.valueOf(0.10));
        snap.setGrossMargin(BigDecimal.valueOf(0.40));
        // evToEbitda left null → only the P/E z-score path is exercised.

        when(yahooProvider.fetchFundamentals(any())).thenReturn(snap);
        when(fundamentalsRepo.findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(any(), any(), any()))
                .thenReturn(history);
        when(fundamentalsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockFundamentals result = service.getLatest("TEST");

        assertEquals(0, BigDecimal.valueOf(1.98).compareTo(result.getPeZScore()),
                "z-score should be 1.98 with the unbiased N−1 estimator");
        assertEquals("EXPENSIVE", result.getValuationLabel());
    }

    @Test
    void valuationLabel_null_whenHistoryBelowMinimum() throws Exception {
        // Only 10 history points (< 60) → z-score cannot be computed → no label.
        List<StockFundamentals> history = new ArrayList<>();
        LocalDate base = LocalDate.now().minusDays(40);
        for (int i = 0; i < 10; i++) {
            history.add(StockFundamentals.builder()
                    .symbol("TEST")
                    .snapshotDate(base.plusDays(i))
                    .trailingPE(BigDecimal.valueOf(20))
                    .build());
        }

        YahooFinancePriceProvider.FundamentalsSnapshot snap =
                new YahooFinancePriceProvider.FundamentalsSnapshot();
        snap.setTrailingPE(BigDecimal.valueOf(30));

        when(yahooProvider.fetchFundamentals(any())).thenReturn(snap);
        when(fundamentalsRepo.findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(any(), any(), any()))
                .thenReturn(history);
        when(fundamentalsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StockFundamentals result = service.getLatest("TEST");

        assertEquals(null, result.getPeZScore());
        assertEquals(null, result.getValuationLabel());
    }
}