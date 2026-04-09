package org.amit.finwise.budget.model;

import jakarta.persistence.*;
import lombok.*;
import org.amit.finwise.expense.model.Expense;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "budgets", indexes = {
    @Index(name = "idx_budget_user_month", columnList = "user_id, budget_month"),
    @Index(name = "idx_budget_category", columnList = "category")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Budget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 7)
    private String budgetMonth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Expense.ExpenseCategory category;

    @Column(nullable = false)
    private BigDecimal budgetLimit;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal spent = BigDecimal.ZERO;

    private BigDecimal remaining;
    private BigDecimal percentSpent;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOverBudget = false;

    @Builder.Default
    private BigDecimal alertThreshold = BigDecimal.valueOf(80);

    @Builder.Default
    private Boolean alertSent = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BudgetStatus status = BudgetStatus.ON_TRACK;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum BudgetStatus {
        ON_TRACK, WARNING, OVER_BUDGET
    }
}
