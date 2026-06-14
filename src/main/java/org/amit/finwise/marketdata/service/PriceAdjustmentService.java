package org.amit.finwise.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.CorporateAction;
import org.amit.finwise.marketdata.model.CorporateActionType;
import org.amit.finwise.marketdata.model.PriceAdjustment;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.PriceAdjustmentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes our own back-adjusted closes from the corporate-action calendar,
 * replacing trust in Yahoo's adjClose. For each instrument we derive a price
 * multiplier per ex-date and write md_eod_price.adj_close = raw close ×
 * (product of factors of all actions whose ex-date is after the trade date).
 *
 * Back-adjustment factors (applied to historical closes before the ex-date):
 *   - Split:    toFaceValue / fromFaceValue           (10→1 ⇒ 0.1)
 *   - Bonus a:b: b / (a + b)                           (1:1 ⇒ 0.5)
 *   - Dividend:  1 − (amount / close_before_ex)        (standard div adjustment)
 * Rights and other actions are recorded but not auto-adjusted (premium-dependent).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceAdjustmentService {

    private static final List<CorporateActionType> ADJUSTING_TYPES = List.of(
            CorporateActionType.SPLIT, CorporateActionType.BONUS, CorporateActionType.DIVIDEND);
    private static final int FACTOR_SCALE = 10;
    private static final int BATCH_SIZE = 1000;

    private final CorporateActionRepository caRepo;
    private final PriceAdjustmentRepository adjRepo;
    private final JdbcTemplate jdbc;

    private record Row(long id, LocalDate tradeDate, BigDecimal close) {}

    /** Baseline: any md_eod_price row without an adj_close gets adj_close = close. */
    public int backfillRawAdjClose() {
        int updated = jdbc.update("UPDATE md_eod_price SET adj_close = close WHERE adj_close IS NULL");
        if (updated > 0) log.info("[PriceAdj] Backfilled adj_close=close for {} rows", updated);
        return updated;
    }

    /** Recompute adj_close for every instrument that has at least one corporate action. */
    public int recomputeAll() {
        backfillRawAdjClose();
        List<Long> ids = caRepo.findDistinctInstrumentIds();
        int touched = 0;
        for (Long id : ids) touched += recomputeForInstrument(id);
        log.info("[PriceAdj] Recomputed adj_close for {} instruments, {} rows touched",
                ids.size(), touched);
        return touched;
    }

    /**
     * Rebuild md_price_adjustment rows for one instrument from its corporate
     * actions and re-derive adj_close for every price row. Returns rows updated.
     */
    @Transactional
    public int recomputeForInstrument(Long instrumentId) {
        List<CorporateAction> actions = caRepo
                .findByInstrumentIdAndActionTypeInAndExDateIsNotNullOrderByExDate(
                        instrumentId, ADJUSTING_TYPES);

        // Rebuild the adjustment ledger for this instrument.
        adjRepo.deleteAll(adjRepo.findByInstrumentIdOrderByExDate(instrumentId));
        List<PriceAdjustment> ledger = new ArrayList<>();
        for (CorporateAction ca : actions) {
            BigDecimal factor = factorFor(instrumentId, ca);
            if (factor == null) continue;
            ledger.add(PriceAdjustment.builder()
                    .instrumentId(instrumentId)
                    .exDate(ca.getExDate())
                    .actionType(ca.getActionType())
                    .factor(factor)
                    .description(ca.getSubject())
                    .build());
        }
        if (!ledger.isEmpty()) adjRepo.saveAll(ledger);

        // adj_close = close × product of factors of all events with ex_date > trade_date.
        List<Row> rows = jdbc.query(
                "SELECT id, trade_date, close FROM md_eod_price WHERE instrument_id = ? ORDER BY trade_date",
                (rs, _) -> new Row(rs.getLong("id"),
                        rs.getObject("trade_date", LocalDate.class),
                        rs.getBigDecimal("close")),
                instrumentId);

        List<Object[]> updates = new ArrayList<>();
        for (Row row : rows) {
            BigDecimal cumulative = BigDecimal.ONE;
            for (PriceAdjustment adj : ledger) {
                if (adj.getExDate().isAfter(row.tradeDate())) {
                    cumulative = cumulative.multiply(adj.getFactor());
                }
            }
            BigDecimal adjClose = row.close() == null ? null
                    : row.close().multiply(cumulative).setScale(6, RoundingMode.HALF_UP);
            updates.add(new Object[]{adjClose, row.id()});
        }
        for (int from = 0; from < updates.size(); from += BATCH_SIZE) {
            jdbc.batchUpdate("UPDATE md_eod_price SET adj_close = ? WHERE id = ?",
                    updates.subList(from, Math.min(from + BATCH_SIZE, updates.size())));
        }
        return updates.size();
    }

    /** The price multiplier for closes before this action's ex-date, or null if uncomputable. */
    BigDecimal factorFor(Long instrumentId, CorporateAction ca) {
        return switch (ca.getActionType()) {
            case SPLIT -> {
                if (ca.getFromFaceValue() == null || ca.getToFaceValue() == null
                        || ca.getFromFaceValue().signum() == 0) yield null;
                yield ca.getToFaceValue().divide(ca.getFromFaceValue(), FACTOR_SCALE, RoundingMode.HALF_UP);
            }
            case BONUS -> {
                if (ca.getRatioNew() == null || ca.getRatioOld() == null
                        || ca.getRatioNew() + ca.getRatioOld() == 0) yield null;
                yield BigDecimal.valueOf(ca.getRatioOld())
                        .divide(BigDecimal.valueOf(ca.getRatioNew() + ca.getRatioOld()),
                                FACTOR_SCALE, RoundingMode.HALF_UP);
            }
            case DIVIDEND -> {
                if (ca.getDividendAmount() == null || ca.getDividendAmount().signum() <= 0) yield null;
                BigDecimal prevClose = closeBefore(instrumentId, ca.getExDate());
                if (prevClose == null || prevClose.signum() <= 0) yield null;
                BigDecimal factor = BigDecimal.ONE.subtract(
                        ca.getDividendAmount().divide(prevClose, FACTOR_SCALE, RoundingMode.HALF_UP));
                yield factor.signum() <= 0 ? null : factor;   // guard against bad/over-large divs
            }
            default -> null;
        };
    }

    /** Last close strictly before the ex-date for this instrument. */
    private BigDecimal closeBefore(Long instrumentId, LocalDate exDate) {
        List<BigDecimal> rs = jdbc.query(
                "SELECT close FROM md_eod_price WHERE instrument_id = ? AND trade_date < ? "
                        + "ORDER BY trade_date DESC LIMIT 1",
                (r, _) -> r.getBigDecimal("close"), instrumentId, exDate);
        return rs.isEmpty() ? null : rs.getFirst();
    }
}
