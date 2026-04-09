package org.amit.finwise.goal.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "financial_goals", indexes = {
    @Index(name = "idx_goal_user", columnList = "user_id"),
    @Index(name = "idx_goal_status", columnList = "status"),
    @Index(name = "idx_goal_target_date", columnList = "target_date"),
    @Index(name = "idx_goal_priority", columnList = "priority")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FinancialGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalType type;

    @Column(nullable = false)
    private BigDecimal targetAmount;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal currentAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private LocalDate targetDate;

    @Column(nullable = false)
    @Builder.Default
    private BigDecimal progressPercentage = BigDecimal.ZERO;

    private BigDecimal requiredMonthlyAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private GoalStatus status = GoalStatus.ON_TRACK;

    @Enumerated(EnumType.STRING)
    private GoalPriority priority;

    @Enumerated(EnumType.STRING)
    private GoalCategory category;

    private BigDecimal expectedAnnualReturn;

    @Builder.Default
    private BigDecimal inflationRate = BigDecimal.valueOf(7);

    @Column(length = 100)
    private String linkedPortfolio;

    @Column(nullable = false)
    private LocalDate startDate;

    private LocalDateTime lastAlertDate;

    @Column(columnDefinition = "TEXT")
    private String offTrackReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum GoalType {
        SAVINGS, INVESTMENT, DEBT_PAYOFF, PURCHASE, EMERGENCY_FUND,
        EDUCATION, RETIREMENT, WEALTH_BUILDING, PROPERTY, VACATION, OTHER
    }

    public enum GoalStatus {
        ON_TRACK, AT_RISK, OFF_TRACK, ACHIEVED, ABANDONED, PAUSED
    }

    public enum GoalPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public enum GoalCategory {
        SHORT_TERM, MEDIUM_TERM, LONG_TERM
    }
}
