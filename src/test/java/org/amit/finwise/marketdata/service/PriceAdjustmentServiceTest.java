package org.amit.finwise.marketdata.service;

import org.amit.finwise.marketdata.model.CorporateAction;
import org.amit.finwise.marketdata.model.CorporateActionType;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.PriceAdjustmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Back-adjustment factor math. The factor multiplies historical closes before
 * the ex-date so the adjusted series is continuous across the action.
 */
class PriceAdjustmentServiceTest {

    private final CorporateActionRepository caRepo = mock(CorporateActionRepository.class);
    private final PriceAdjustmentRepository adjRepo = mock(PriceAdjustmentRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PriceAdjustmentService service =
            new PriceAdjustmentService(caRepo, adjRepo, jdbc);

    @Test
    void splitFactorIsNewOverOldFaceValue() {
        CorporateAction ca = CorporateAction.builder()
                .actionType(CorporateActionType.SPLIT)
                .fromFaceValue(new BigDecimal("10"))
                .toFaceValue(new BigDecimal("1"))
                .exDate(LocalDate.of(2024, 1, 5))
                .build();
        // 10 -> 1 split: pre-split prices scale by 0.1
        assertEquals(0, new BigDecimal("0.1").compareTo(service.factorFor(1L, ca)));
    }

    @Test
    void bonusFactorIsHeldOverTotal() {
        CorporateAction oneForOne = CorporateAction.builder()
                .actionType(CorporateActionType.BONUS).ratioNew(1).ratioOld(1).build();
        // 1:1 bonus doubles shares: prices halve
        assertEquals(0, new BigDecimal("0.5").compareTo(service.factorFor(1L, oneForOne)));

        CorporateAction oneForTwo = CorporateAction.builder()
                .actionType(CorporateActionType.BONUS).ratioNew(1).ratioOld(2).build();
        // 1:2 bonus: 2 held -> 3 total, factor 2/3
        assertEquals(0, BigDecimal.valueOf(2).divide(BigDecimal.valueOf(3), 10, java.math.RoundingMode.HALF_UP)
                .compareTo(service.factorFor(1L, oneForTwo)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dividendFactorUsesCloseBeforeEx() {
        when(jdbc.query(any(String.class), any(RowMapper.class), eq(1L), eq(LocalDate.of(2024, 1, 19))))
                .thenReturn(List.of(new BigDecimal("100")));
        CorporateAction div = CorporateAction.builder()
                .actionType(CorporateActionType.DIVIDEND)
                .dividendAmount(new BigDecimal("5"))
                .exDate(LocalDate.of(2024, 1, 19))
                .build();
        // 1 - (5/100) = 0.95
        assertEquals(0, new BigDecimal("0.95").compareTo(service.factorFor(1L, div)));
    }

    @Test
    void uncomputableActionsReturnNull() {
        assertNull(service.factorFor(1L, CorporateAction.builder()
                .actionType(CorporateActionType.SPLIT).build()));             // missing face values
        assertNull(service.factorFor(1L, CorporateAction.builder()
                .actionType(CorporateActionType.RIGHTS).ratioNew(1).ratioOld(2).build())); // not adjusted
    }
}
