package org.amit.finwise.expense.dto;

import org.amit.finwise.expense.model.Expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ExpenseResponse(
        Long id,
        BigDecimal amount,
        Expense.ExpenseCategory category,
        String description,
        Expense.ExpenseSource source,
        LocalDate expenseDate,
        String merchant,
        Expense.PaymentMethod paymentMethod,
        Boolean isRecurring,
        String notes,
        String budgetCategory,
        LocalDateTime createdAt
) {
    public static ExpenseResponse from(Expense e) {
        return new ExpenseResponse(
                e.getId(),
                e.getAmount(),
                e.getCategory(),
                e.getDescription(),
                e.getSource(),
                e.getExpenseDate(),
                e.getMerchant(),
                e.getPaymentMethod(),
                e.getIsRecurring(),
                e.getNotes(),
                e.getBudgetCategory(),
                e.getCreatedAt()
        );
    }
}
