package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_investor_behavior", indexes = {
    @Index(name = "idx_investor_behavior_user", columnList = "user_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestorBehaviorProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    // ── Trade frequency ───────────────────────────────────────────────────────

    /** Trades per month averaged over last 12 months */
    private Double tradesPerMonth;

    /** Derived label from tradesPerMonth */
    @Enumerated(EnumType.STRING)
    private TradingStyle tradingStyle;

    // ── Holding duration ──────────────────────────────────────────────────────

    /** Average days between BUY and paired SELL for closed positions (FIFO) */
    private Double avgHoldingDurationDays;

    /**
     * Patience index: 0.0 (day trader) to 1.0 (multi-year holder).
     * = min(avgHoldingDurationDays / 365.0, 1.0)
     */
    private Double patienceIndex;

    // ── Panic sell detection ──────────────────────────────────────────────────

    /**
     * Number of SELL transactions made within 5 days of a HIGH-relevance NEGATIVE
     * article mentioning the same symbol.
     */
    private Integer panicSellCount;

    /** panicSellCount / totalSellCount — 0.0 = disciplined, 1.0 = fully reactive */
    private Double panicSellRatio;

    // ── Concentration ─────────────────────────────────────────────────────────

    /** Number of distinct active symbols in portfolio */
    private Integer activeSymbolCount;

    /**
     * Herfindahl-Hirschman Index: sum of (weight_i)^2.
     * 1.0 = fully concentrated in one stock; approaches 0 = perfectly diversified.
     */
    private Double concentrationHHI;

    // ── Sector affinity ───────────────────────────────────────────────────────

    /**
     * JSON map of sector → trade count over last 12 months.
     * e.g. {"Banking": 12, "IT": 5, "Pharma": 2}
     */
    @Column(columnDefinition = "TEXT")
    private String sectorAffinityJson;

    // ── Win rate ──────────────────────────────────────────────────────────────

    /** Fraction of closed positions (BUY→SELL pairs) that were profitable */
    private Double winRate;

    /** Total number of closed positions analyzed */
    private Integer closedPositionsAnalyzed;

    // ── Derived risk ──────────────────────────────────────────────────────────

    /**
     * Behavior-inferred risk appetite — may differ from stated UserProfile.riskAppetite.
     * Computed from tradingStyle + patienceIndex + concentrationHHI + panicSellRatio.
     */
    @Enumerated(EnumType.STRING)
    private UserProfile.RiskAppetite derivedRiskAppetite;

    /**
     * Divergence between stated risk (questionnaire) and derived risk (behavior).
     * 0 = no divergence; 100 = maximum divergence.
     * Triggers psychologicalGuardrail in PersonalizedRelevanceScorer when > 60.
     */
    private Integer hypocrisyScore;

    // ── Cache metadata ────────────────────────────────────────────────────────

    @Column(nullable = false)
    private LocalDateTime computedAt;

    private Integer transactionCount;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum TradingStyle {
        SCALPER,          // > 10 trades/month
        ACTIVE_TRADER,    // 3–10 trades/month
        LONG_TERM_HOLDER  // < 3 trades/month
    }
}
