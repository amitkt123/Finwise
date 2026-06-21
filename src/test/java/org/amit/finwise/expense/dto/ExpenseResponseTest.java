package org.amit.finwise.expense.dto;

import org.amit.finwise.expense.model.Expense;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseResponseTest {

    @Test
    void from_mapsAllPublicFields() {
        Expense e = Expense.builder()
                .id(42L)
                .userId("amit")
                .amount(BigDecimal.valueOf(500))
                .category(Expense.ExpenseCategory.FOOD_DINING)
                .description("Dinner")
                .source(Expense.ExpenseSource.MANUAL)
                .expenseDate(LocalDate.of(2026, 6, 20))
                .merchant("Swiggy")
                .paymentMethod(Expense.PaymentMethod.UPI)
                .isRecurring(false)
                .notes("team dinner")
                .budgetCategory("dining")
                .build();

        ExpenseResponse dto = ExpenseResponse.from(e);

        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.amount()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(dto.category()).isEqualTo(Expense.ExpenseCategory.FOOD_DINING);
        assertThat(dto.description()).isEqualTo("Dinner");
        assertThat(dto.source()).isEqualTo(Expense.ExpenseSource.MANUAL);
        assertThat(dto.expenseDate()).isEqualTo(LocalDate.of(2026, 6, 20));
        assertThat(dto.merchant()).isEqualTo("Swiggy");
        assertThat(dto.paymentMethod()).isEqualTo(Expense.PaymentMethod.UPI);
        assertThat(dto.isRecurring()).isFalse();
        assertThat(dto.notes()).isEqualTo("team dinner");
        assertThat(dto.budgetCategory()).isEqualTo("dining");
    }

    @Test
    void from_doesNotExposeUserId() throws NoSuchFieldException {
        // Verify record has no userId field — compile-time guarantee that userId isn't in the DTO
        try {
            ExpenseResponse.class.getDeclaredField("userId");
            throw new AssertionError("ExpenseResponse must not expose userId");
        } catch (NoSuchFieldException expected) {
            // correct — userId is hidden
        }
    }
}
