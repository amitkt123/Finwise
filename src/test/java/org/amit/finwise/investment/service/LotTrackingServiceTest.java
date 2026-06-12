package org.amit.finwise.investment.service;

import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * FIFO lot-ledger tests with hand-computed rupee outcomes.
 */
@ExtendWith(MockitoExtension.class)
class LotTrackingServiceTest {

    @Mock TransactionRepository transactionRepository;
    @InjectMocks LotTrackingService service;

    private static final String USER = "u";

    @Test
    void sellConsumesOldestLotsFirstAndSplitsAcrossLots() {
        // BUY 10 @100 (2023-01-10), BUY 10 @200 (2024-06-10), SELL 15 @300 (2025-01-10)
        // FIFO: 10 from lot1 (gain 10×200=2000, long-term) + 5 from lot2 (gain 5×100=500, short-term)
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(
                buy("TCS", "2023-01-10", 10, 100),
                buy("TCS", "2024-06-10", 10, 200),
                sell("TCS", "2025-01-10", 15, 300)));

        LotTrackingService.LotLedger ledger = service.buildLedger(USER);

        assertEquals(2, ledger.realizedGains().size());
        LotTrackingService.RealizedGain first = ledger.realizedGains().get(0);
        assertEquals(LocalDate.parse("2023-01-10"), first.buyDate());
        assertEquals(2000.0, first.gain(), 1e-9);
        assertTrue(first.longTerm(), "held 2 years — long-term");

        LotTrackingService.RealizedGain second = ledger.realizedGains().get(1);
        assertEquals(LocalDate.parse("2024-06-10"), second.buyDate());
        assertEquals(500.0, second.gain(), 1e-9);
        assertFalse(second.longTerm(), "held 7 months — short-term");

        // Open ledger: 5 units of the 2024 lot remain
        List<LotTrackingService.HoldingLot> open = ledger.openLotsBySymbol().get("TCS");
        assertEquals(1, open.size());
        assertEquals(0, open.get(0).quantity().compareTo(BigDecimal.valueOf(5)));
        assertEquals(0, open.get(0).costPerUnit().compareTo(BigDecimal.valueOf(200)));
    }

    @Test
    void sellWithoutRecordedBuy_isFlaggedNotInvented() {
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(
                buy("INFY", "2024-01-10", 5, 1400),
                sell("INFY", "2024-08-10", 8, 1600)));

        LotTrackingService.LotLedger ledger = service.buildLedger(USER);

        assertEquals(1, ledger.realizedGains().size(), "only the matched 5 units realize a gain");
        assertEquals(1000.0, ledger.realizedGains().get(0).gain(), 1e-9);
        assertTrue(ledger.notes().stream().anyMatch(n -> n.startsWith("UNMATCHED_SELL: INFY")),
                "the 3 unmatched units must be disclosed, got " + ledger.notes());
        assertFalse(ledger.openLotsBySymbol().containsKey("INFY"));
    }

    @Test
    void longTermBoundary_isStrictlyMoreThanOneYear() {
        LotTrackingService.HoldingLot exactlyOneYear =
                new LotTrackingService.HoldingLot("X", LocalDate.parse("2024-06-12"), BigDecimal.ONE, BigDecimal.TEN);
        assertFalse(exactlyOneYear.isLongTermAsOf(LocalDate.parse("2025-06-12")),
                "exactly 1 year is still short-term");
        assertTrue(exactlyOneYear.isLongTermAsOf(LocalDate.parse("2025-06-13")));
        assertEquals(0, exactlyOneYear.daysToLongTerm(LocalDate.parse("2025-06-13")));
        assertEquals(30, exactlyOneYear.daysToLongTerm(LocalDate.parse("2025-05-13")));
    }

    @Test
    void missingPriceFallsBackToAmountOverQuantity() {
        Transaction t = buy("HDFC", "2024-01-10", 4, 0);
        t.setPricePerUnit(null);
        t.setAmount(BigDecimal.valueOf(6000));
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(t));

        LotTrackingService.LotLedger ledger = service.buildLedger(USER);
        assertEquals(0, ledger.openLotsBySymbol().get("HDFC").get(0)
                .costPerUnit().compareTo(BigDecimal.valueOf(1500)));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Transaction buy(String sym, String date, double qty, double price) {
        return txn(sym, date, qty, price, Transaction.TransactionType.BUY);
    }

    private static Transaction sell(String sym, String date, double qty, double price) {
        return txn(sym, date, qty, price, Transaction.TransactionType.SELL);
    }

    private static Transaction txn(String sym, String date, double qty, double price,
                                   Transaction.TransactionType type) {
        return Transaction.builder()
                .userId(USER)
                .symbol(sym)
                .transactionDate(LocalDate.parse(date))
                .transactionType(type)
                .quantity(BigDecimal.valueOf(qty))
                .pricePerUnit(BigDecimal.valueOf(price))
                .amount(BigDecimal.valueOf(qty * price))
                .build();
    }
}
