package org.amit.finwise.investment.dto;

import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentResponseTest {

    @Test
    void from_mapsAllPublicFields() {
        Investment inv = Investment.builder()
                .id(7L)
                .userId("amit")
                .type(InvestmentType.STOCK)
                .symbol("HDFCBANK")
                .name("HDFC Bank")
                .platform("Groww")
                .sector("Banking")
                .purchaseDate(LocalDate.of(2024, 1, 15))
                .quantity(BigDecimal.TEN)
                .costPerUnit(BigDecimal.valueOf(1500))
                .totalCost(BigDecimal.valueOf(15000))
                .currentPrice(BigDecimal.valueOf(1600))
                .currentValue(BigDecimal.valueOf(16000))
                .unrealizedGainLoss(BigDecimal.valueOf(1000))
                .gainLossPercentage(BigDecimal.valueOf(6.67))
                .isActive(true)
                .build();

        InvestmentResponse dto = InvestmentResponse.from(inv);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.type()).isEqualTo(InvestmentType.STOCK);
        assertThat(dto.symbol()).isEqualTo("HDFCBANK");
        assertThat(dto.name()).isEqualTo("HDFC Bank");
        assertThat(dto.platform()).isEqualTo("Groww");
        assertThat(dto.quantity()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(dto.costPerUnit()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(dto.totalCost()).isEqualByComparingTo(BigDecimal.valueOf(15000));
        assertThat(dto.currentValue()).isEqualByComparingTo(BigDecimal.valueOf(16000));
        assertThat(dto.unrealizedGainLoss()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(dto.isActive()).isTrue();
    }

    @Test
    void from_doesNotExposeUserId() {
        try {
            InvestmentResponse.class.getDeclaredField("userId");
            throw new AssertionError("InvestmentResponse must not expose userId");
        } catch (NoSuchFieldException expected) {
            // correct
        }
    }
}
