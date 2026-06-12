package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.service.StockPriceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Hand-computed TWRR + benchmark-relative fixture.
 *
 * 5 snapshot days, 10 days apart (40 covered days). One ₹10,000 BUY between
 * day 0 and day 1 sized so r₁ = 0; then clean ±10% sub-period returns:
 *   portfolio: 1.0 × 1.1 × 0.9 × 1.1 − 1 = +8.9% cumulative
 *   benchmark: 1.0 × 1.05 × 0.975 × 1.05 − 1 = +7.49375%
 * Active sub-period returns: {0, +0.05, −0.075, +0.05}.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioPerformanceServiceTest {

    @Mock PortfolioSnapshotRepository snapshotRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock StockPriceHistoryRepository priceHistoryRepository;
    @InjectMocks PortfolioPerformanceService service;

    private static final String USER = "u";
    private static final LocalDate D0 = LocalDate.of(2025, 1, 1);

    @Test
    void twrrAndBenchmarkRelativeMetrics_matchHandComputedFixture() {
        mockSnapshots(100_000, 110_000, 121_000, 108_900, 119_790);
        // BUY ₹10,000 on Jan 5 → r₁ = 110000/(100000+10000) − 1 = 0
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(
                Transaction.builder().userId(USER).symbol("X")
                        .transactionType(Transaction.TransactionType.BUY)
                        .transactionDate(D0.plusDays(4))
                        .amount(BigDecimal.valueOf(10_000)).build()));
        mockBenchmark(20_000, 20_000, 21_000, 20_475, 21_498.75);

        PortfolioPerformanceService.TwrrResult r = service.computeTwrr(USER).orElseThrow();

        assertEquals(0.089, r.twrr(), 1e-9, "1.0 × 1.1 × 0.9 × 1.1 − 1");
        assertEquals(4, r.subPeriods());
        double years = 40.0 / 365.0;
        assertEquals(Math.pow(1.089, 1 / years) - 1, r.annualizedTwrr(), 1e-9);

        assertEquals(0.0749375, r.benchmarkTwrr(), 1e-9, "1.05 × 0.975 × 1.05 − 1");
        double annBench = Math.pow(1.0749375, 1 / years) - 1;
        assertEquals(annBench, r.annualizedBenchmarkTwrr(), 1e-9);
        double annPort = Math.pow(1.089, 1 / years) - 1;
        assertEquals(annPort - annBench, r.activeReturnAnnualized(), 1e-9);

        // Active returns {0, .05, −.075, .05}: mean .00625,
        // N−1 variance = 0.01046875/3, annualized by 4 obs / 40d → ×√36.5
        double sd = Math.sqrt(0.01046875 / 3.0);
        double te = sd * Math.sqrt(4 * 365.0 / 40.0);
        assertEquals(te, r.trackingErrorAnnualized(), 1e-9);
        assertEquals((annPort - annBench) / te, r.informationRatio(), 1e-9);
    }

    @Test
    void missingBenchmarkData_leavesRelativeFieldsNaNButKeepsTwrr() {
        mockSnapshots(100_000, 110_000, 121_000, 108_900, 119_790);
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of());
        when(priceHistoryRepository.findRecentBySymbol(anyString(), any(LocalDate.class)))
                .thenReturn(List.of());

        PortfolioPerformanceService.TwrrResult r = service.computeTwrr(USER).orElseThrow();

        assertFalse(Double.isNaN(r.twrr()));
        assertTrue(Double.isNaN(r.benchmarkTwrr()), "no benchmark data → NaN, never a guess");
        assertTrue(Double.isNaN(r.informationRatio()));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void mockSnapshots(double... values) {
        List<PortfolioSnapshot> snapshots = new ArrayList<>();
        for (int i = values.length - 1; i >= 0; i--) { // repository returns DESC
            snapshots.add(PortfolioSnapshot.builder()
                    .userId(USER)
                    .snapshotTime(D0.plusDays(10L * i).atTime(18, 0))
                    .currentValue(BigDecimal.valueOf(values[i]))
                    .build());
        }
        when(snapshotRepository.findRecentSnapshots(org.mockito.ArgumentMatchers.eq(USER),
                any(LocalDateTime.class))).thenReturn(snapshots);
    }

    private void mockBenchmark(double... closes) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            rows.add(StockPriceHistory.builder()
                    .symbol(StockPriceService.NIFTY_SYMBOL)
                    .priceDate(D0.plusDays(10L * i))
                    .adjustedClose(BigDecimal.valueOf(closes[i]))
                    .build());
        }
        lenient().when(priceHistoryRepository.findRecentBySymbol(
                org.mockito.ArgumentMatchers.eq(StockPriceService.NIFTY_SYMBOL),
                any(LocalDate.class))).thenReturn(rows);
    }
}
