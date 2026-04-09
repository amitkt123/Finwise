package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_ai_insights", indexes = {
    @Index(name = "idx_insight_date", columnList = "insight_date DESC"),
    @Index(name = "idx_insight_type", columnList = "insight_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private LocalDate insightDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InsightType insightType;

    /** Short title/headline for the insight */
    @Column(nullable = false)
    private String title;

    /** Full LLM-generated content (Markdown) */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** LLM model used to generate this */
    private String modelUsed;

    /** Context tokens used (for monitoring) */
    private Integer tokensUsed;

    /** Whether this insight was emailed */
    @Builder.Default
    private Boolean emailed = false;

    /** Whether user has read this insight */
    @Builder.Default
    private Boolean read = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum InsightType {
        DAILY_BRIEF,       // Morning CFO brief
        PORTFOLIO_ALERT,   // Significant portfolio change
        GOAL_ADVICE,       // Goal-specific recommendation
        NEWS_SUMMARY,      // Curated news digest
        REBALANCE_ALERT,   // Portfolio rebalancing needed
        MARKET_INSIGHT,    // Market conditions commentary
        EXPENSE_ALERT,     // Spending pattern alert
        CHAT_RESPONSE      // Conversational response
    }
}
