package org.amit.finwise.cfo.service;

import org.amit.finwise.cfo.model.DataQualityFlag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Gap classifier (DF-2): a large move on a known corporate-action ex-date is
 * EXPECTED_CORPORATE_ACTION; the same move with no action on file is SUSPECT_GAP;
 * ordinary circuit-sized moves stay OK.
 */
class StockPriceServiceClassifyMoveTest {

    private static final LocalDate EX = LocalDate.of(2024, 1, 5);

    @Test
    void largeMoveOnExDateIsExpectedCorporateAction() {
        assertEquals(DataQualityFlag.EXPECTED_CORPORATE_ACTION,
                StockPriceService.classifyMove(-50.0, EX, Set.of(EX)));
    }

    @Test
    void largeMoveWithoutCorporateActionIsSuspectGap() {
        assertEquals(DataQualityFlag.SUSPECT_GAP,
                StockPriceService.classifyMove(-50.0, EX, Set.of()));
    }

    @Test
    void splitSizedMoveNoLongerSlipsThroughAsCircuit() {
        // 1:2 split ≈ -50%: previously below the old -70% gate, now caught.
        assertEquals(DataQualityFlag.SUSPECT_GAP,
                StockPriceService.classifyMove(-50.0, EX, Set.of(LocalDate.of(2024, 1, 4))));
    }

    @Test
    void ordinaryCircuitMoveStaysOk() {
        assertEquals(DataQualityFlag.OK, StockPriceService.classifyMove(-19.5, EX, Set.of(EX)));
        assertEquals(DataQualityFlag.OK, StockPriceService.classifyMove(9.8, EX, Set.of()));
    }

    @Test
    void nullChangeIsOk() {
        assertEquals(DataQualityFlag.OK, StockPriceService.classifyMove(null, EX, Set.of(EX)));
    }
}
