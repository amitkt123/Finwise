package org.amit.expensetracker.expense.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "expenses", indexes = {
    @Index(name = "idx_expense_date", columnList = "expense_date DESC"),
    @Index(name = "idx_expense_category", columnList = "category"),
    @Index(name = "idx_expense_amount", columnList = "amount DESC"),
    @Index(name = "idx_expense_user", columnList = "user_id"),
    @Index(name = "idx_expense_month", columnList = "expense_date DESC, category")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseCategory category;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExpenseSource source;

    @Column(nullable = false)
    private LocalDate expenseDate;

    @Column(length = 255)
    private String merchant;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @Builder.Default
    private Boolean isRecurring = false;

    @Enumerated(EnumType.STRING)
    private RecurringFrequency recurringFrequency;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(length = 100)
    private String budgetCategory;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum ExpenseCategory {
        FOOD_DINING, GROCERIES, TRANSPORTATION, UTILITIES, ENTERTAINMENT,
        SHOPPING, HEALTHCARE, INSURANCE, SUBSCRIPTION, INVESTMENT,
        SAVINGS, EDUCATION, PERSONAL_CARE, HOME_MAINTENANCE, TRAVEL,
        BUSINESS, CHARITY, DEBT_PAYMENT, TAXES, OTHER
    }

    public enum ExpenseSource {
        SMS, MANUAL, BANK_API, APP_INTEGRATION, BANK_PDF, MF_PDF, UPI_PDF
    }

    public enum PaymentMethod {
        CREDIT_CARD, DEBIT_CARD, CASH, BANK_TRANSFER, UPI, WALLET, OTHER
    }

    public enum RecurringFrequency {
        DAILY, WEEKLY, MONTHLY, QUARTERLY, YEARLY
    }
}
