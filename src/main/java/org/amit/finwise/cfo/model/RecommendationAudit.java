package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recommendation_audit",
       indexes = @Index(columnList = "user_id, generated_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String type;         // REBALANCE | BUY | SELL | HOLD | GOAL_ADJUST | STRESS_FLAG | REPORT

    private String symbol;       // nullable — portfolio-level recommendations have no symbol

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rationale;    // Java-rendered reasoning; never LLM output

    private Double confidence;

    @Column(columnDefinition = "TEXT")
    private String conflictState; // snapshot of ConflictDisclosureConfig.conflictStatement

    @Column(columnDefinition = "TEXT")
    private String dataSourcesJson; // JSON array of source strings

    private String engineVersion;

    @Column(name = "generated_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime generatedAt;

    private boolean userAcked = false;

    private String outcome;      // filled post-hoc by EventOutcomeService

    private Instant outcomeAt;
}
