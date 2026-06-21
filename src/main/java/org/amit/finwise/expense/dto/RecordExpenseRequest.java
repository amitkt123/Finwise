package org.amit.finwise.expense.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.amit.finwise.expense.model.Expense;

import java.math.BigDecimal;

public record RecordExpenseRequest(
        @NotNull @Positive BigDecimal amount,
        @NotNull Expense.ExpenseCategory category,
        @NotBlank String description
) {}
