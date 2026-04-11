package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_impacts", indexes = {
    @Index(name = "idx_policy_impact_doc", columnList = "document_id"),
    @Index(name = "idx_policy_impact_subject", columnList = "subject_type, subject_key"),
    @Index(name = "idx_policy_impact_area", columnList = "policy_area"),
    @Index(name = "idx_policy_impact_effective", columnList = "effective_from")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private PolicyDocument document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "version_id")
    private PolicyDocumentVersion version;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_area", nullable = false, length = 50)
    private PolicyArea policyArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 50)
    private PolicySubjectType subjectType;

    @Column(name = "subject_key", nullable = false, length = 100)
    private String subjectKey;

    @Column(name = "subject_label", nullable = false, length = 200)
    private String subjectLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PolicyImpactDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PolicyImpactHorizon horizon;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "impact_summary", nullable = false, columnDefinition = "TEXT")
    private String impactSummary;

    @Column(name = "reasoning_note", columnDefinition = "TEXT")
    private String reasoningNote;

    @Column(name = "tags_csv", length = 1000)
    private String tagsCsv;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
