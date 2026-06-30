package org.amit.finwise.broker.service;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingDeduplicationServiceTest {

    private final HoldingDeduplicationService svc = new HoldingDeduplicationService();

    @Test
    void singleHolding_passesThrough() {
        var h = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        List<MergedHoldingDTO> merged = svc.merge(List.of(h));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).totalQuantity()).isEqualByComparingTo("10");
        assertThat(merged.get(0).blendedAvgCost()).isEqualByComparingTo("2500");
    }

    @Test
    void sameisin_twoBrokers_mergesWithWeightedAvgCost() {
        // Zerodha: 10 @ 2500 = 25000 cost basis
        // Dhan: 5 @ 2700 = 13500 cost basis
        // Blended: (25000 + 13500) / 15 = 2566.67
        var z = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        var d = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.DHAN, new BigDecimal("5"), new BigDecimal("2700"), new BigDecimal("13500"));

        List<MergedHoldingDTO> merged = svc.merge(List.of(z, d));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).totalQuantity()).isEqualByComparingTo("15");
        assertThat(merged.get(0).blendedAvgCost())
            .isEqualByComparingTo(new BigDecimal("2566.67"));
        assertThat(merged.get(0).brokerBreakdown()).containsKey(BrokerEnum.ZERODHA);
        assertThat(merged.get(0).brokerBreakdown()).containsKey(BrokerEnum.DHAN);
    }

    @Test
    void differentIsins_keptSeparate() {
        var r = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        var t = new BrokerHoldingDTO("INE467B01029", "TCS", "TCS",
            BrokerEnum.ZERODHA, new BigDecimal("5"), new BigDecimal("3500"), new BigDecimal("18500"));
        assertThat(svc.merge(List.of(r, t))).hasSize(2);
    }
}
