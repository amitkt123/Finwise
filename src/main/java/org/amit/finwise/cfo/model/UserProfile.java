package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_user_profiles")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    private String name;
    private String email;

    /** Monthly take-home income in INR */
    private BigDecimal monthlyIncome;

    /** Monthly fixed expenses (rent, EMIs, etc.) */
    private BigDecimal monthlyFixedExpenses;

    /** Investment appetite: CONSERVATIVE | MODERATE | AGGRESSIVE */
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private RiskAppetite riskAppetite = RiskAppetite.MODERATE;

    /** Investment horizon in years */
    @Builder.Default
    private Integer investmentHorizonYears = 5;

    /** Target monthly savings amount */
    private BigDecimal targetMonthlySavings;

    /** Target portfolio value */
    private BigDecimal targetPortfolioValue;

    /** Primary financial goal summary (free text for LLM context) */
    @Column(columnDefinition = "TEXT")
    private String primaryGoalDescription;

    /** Additional context for CFO (lifestyle, obligations, preferences) */
    @Column(columnDefinition = "TEXT")
    private String additionalContext;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum RiskAppetite {
        CONSERVATIVE, MODERATE, AGGRESSIVE
    }
}
