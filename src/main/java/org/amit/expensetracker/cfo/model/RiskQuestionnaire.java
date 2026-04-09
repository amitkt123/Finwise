package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_risk_questionnaire")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskQuestionnaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    // ── Questionnaire answers ─────────────────────────────────────────────────

    /** How does the investor react to a 20% portfolio drawdown? */
    @Enumerated(EnumType.STRING)
    private DrawdownReaction drawdownReaction;

    /** Self-reported investment horizon in years (1–3, 3–7, 7–15, 15+) */
    private Integer investmentHorizonYears;

    /** Income stability: 1 (freelance/variable) to 5 (very stable salaried) */
    private Integer incomeStabilityRating;

    /** Months of expenses in liquid emergency fund */
    private Integer emergencyFundMonths;

    /** Maximum portfolio loss the investor can psychologically tolerate (%) */
    private Integer maxAcceptableLossPercent;

    /** Primary investment objective */
    @Enumerated(EnumType.STRING)
    private PrimaryGoalFocus primaryGoalFocus;

    // ── Derived from answers ──────────────────────────────────────────────────

    /**
     * Computed risk score from questionnaire answers: 0–100.
     * Higher = more aggressive / risk-tolerant.
     * Used alongside derivedRiskAppetite (behavior-based) to compute hypocrisyScore.
     */
    private Integer statedRiskScore;

    private LocalDateTime completedAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum DrawdownReaction {
        SELL_IMMEDIATELY,  // panic exit
        HOLD_AND_WAIT,     // stay the course
        BUY_MORE           // treat as opportunity
    }

    public enum PrimaryGoalFocus {
        CAPITAL_PRESERVATION,  // don't lose money
        REGULAR_INCOME,        // dividends / interest
        BALANCED,              // growth + income
        CAPITAL_GROWTH         // maximum long-term appreciation
    }
}
