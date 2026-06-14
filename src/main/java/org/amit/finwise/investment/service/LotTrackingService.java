package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a per-symbol FIFO tax-lot ledger from BUY/SELL transaction history.
 *
 * Indian capital-gains rules apply FIFO per demat account (§45 read with the
 * CBDT FIFO circular), so the lot a SELL consumes is the oldest open BUY.
 * The ledger is derived on demand and never persisted — transactions are the
 * source of truth.
 *
 * Groww's holdings API exposes only an average price, so when a symbol has no
 * transaction history the caller must fall back to the single
 * {@code Investment.purchaseDate} lot (a note is emitted for transparency).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotTrackingService {

    private final TransactionRepository transactionRepository;

    /** An open (not yet fully sold) FIFO lot. */
    public record HoldingLot(
            String symbol,
            LocalDate buyDate,
            BigDecimal quantity,
            BigDecimal costPerUnit
    ) {
        /** LTCG boundary: held strictly more than 1 year. */
        public boolean isLongTermAsOf(LocalDate asOf) {
            return buyDate.isBefore(asOf.minusYears(1));
        }

        /** Days remaining until this lot turns long-term (0 if already there). */
        public long daysToLongTerm(LocalDate asOf) {
            LocalDate boundary = buyDate.plusYears(1);
            return boundary.isAfter(asOf) ? java.time.temporal.ChronoUnit.DAYS.between(asOf, boundary) : 0;
        }
    }

    /** One SELL matched against one consumed BUY lot. */
    public record RealizedGain(
            String symbol,
            LocalDate buyDate,
            LocalDate sellDate,
            BigDecimal quantity,
            BigDecimal costPerUnit,
            BigDecimal sellPricePerUnit,
            boolean longTerm
    ) {
        public double gain() {
            return sellPricePerUnit.subtract(costPerUnit).multiply(quantity).doubleValue();
        }
    }

    public record LotLedger(
            Map<String, List<HoldingLot>> openLotsBySymbol,
            List<RealizedGain> realizedGains,
            List<String> notes
    ) {}

    public LotLedger buildLedger(String userId) {
        List<Transaction> txns = transactionRepository.findBuySellTransactionsAsc(userId);

        Map<String, Deque<MutableLot>> open = new LinkedHashMap<>();
        List<RealizedGain> realized = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (Transaction t : txns) {
            String sym = t.getSymbol().toUpperCase();
            BigDecimal qty = t.getQuantity();
            BigDecimal price = pricePerUnit(t);
            if (qty == null || qty.signum() <= 0 || price == null) {
                notes.add("LOT_SKIPPED: " + sym + " " + t.getTransactionDate()
                        + " " + t.getTransactionType() + " — missing quantity/price");
                continue;
            }

            if (t.getTransactionType() == Transaction.TransactionType.BUY) {
                open.computeIfAbsent(sym, _ -> new ArrayDeque<>())
                        .addLast(new MutableLot(t.getTransactionDate(), qty, price));
                continue;
            }

            // SELL: consume oldest lots first
            BigDecimal remaining = qty;
            Deque<MutableLot> lots = open.get(sym);
            while (remaining.signum() > 0 && lots != null && !lots.isEmpty()) {
                MutableLot lot = lots.peekFirst();
                BigDecimal consumed = lot.quantity.min(remaining);
                boolean longTerm = lot.buyDate.isBefore(t.getTransactionDate().minusYears(1));
                realized.add(new RealizedGain(sym, lot.buyDate, t.getTransactionDate(),
                        consumed, lot.costPerUnit, price, longTerm));
                lot.quantity = lot.quantity.subtract(consumed);
                remaining = remaining.subtract(consumed);
                if (lot.quantity.signum() <= 0) lots.removeFirst();
            }
            if (remaining.signum() > 0) {
                notes.add(String.format(
                        "UNMATCHED_SELL: %s %s — %s units sold without a recorded BUY (history starts mid-stream); gains for these units not computed",
                        sym, t.getTransactionDate(), remaining.stripTrailingZeros().toPlainString()));
            }
        }

        Map<String, List<HoldingLot>> openLots = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<MutableLot>> e : open.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<HoldingLot> lots = new ArrayList<>();
            for (MutableLot lot : e.getValue()) {
                lots.add(new HoldingLot(e.getKey(), lot.buyDate, lot.quantity, lot.costPerUnit));
            }
            openLots.put(e.getKey(), lots);
        }
        return new LotLedger(openLots, realized, notes);
    }

    private static BigDecimal pricePerUnit(Transaction t) {
        if (t.getPricePerUnit() != null) return t.getPricePerUnit();
        if (t.getAmount() != null && t.getQuantity() != null && t.getQuantity().signum() > 0) {
            return t.getAmount().divide(t.getQuantity(), 6, RoundingMode.HALF_UP);
        }
        return null;
    }

    private static final class MutableLot {
        final LocalDate buyDate;
        BigDecimal quantity;
        final BigDecimal costPerUnit;

        MutableLot(LocalDate buyDate, BigDecimal quantity, BigDecimal costPerUnit) {
            this.buyDate = buyDate;
            this.quantity = quantity;
            this.costPerUnit = costPerUnit;
        }
    }
}
