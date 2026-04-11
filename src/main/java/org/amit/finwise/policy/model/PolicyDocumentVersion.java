package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_document_versions", indexes = {
    @Index(name = "idx_policy_version_doc", columnList = "document_id"),
    @Index(name = "idx_policy_version_current", columnList = "is_current"),
    @Index(name = "idx_policy_version_hash", columnList = "content_hash")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyDocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private PolicyDocument document;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "version_label", length = 100)
    private String versionLabel;

    @Column(name = "supersedes_reference", length = 200)
    private String supersedesReference;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean current = true;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Column(name = "normalized_text", nullable = false, columnDefinition = "TEXT")
    private String normalizedText;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
