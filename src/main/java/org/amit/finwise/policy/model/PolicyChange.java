package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "policy_changes", indexes = {
    @Index(name = "idx_policy_change_doc", columnList = "document_id"),
    @Index(name = "idx_policy_change_subject", columnList = "subject_key"),
    @Index(name = "idx_policy_change_to_version", columnList = "to_version_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private PolicyDocument document;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_version_id")
    private PolicyDocumentVersion fromVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_version_id", nullable = false)
    private PolicyDocumentVersion toVersion;

    /** Subject key this change is relevant for. Null = document-level diff. */
    @Column(name = "subject_key", length = 100)
    private String subjectKey;

    /** LLM-generated one-line summary, e.g. "CRR raised from 4.0% to 4.5%". */
    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    /** Plain-English beginner narrative of what changed and why it matters. */
    @Column(name = "change_narrative", columnDefinition = "TEXT")
    private String changeNarrative;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
