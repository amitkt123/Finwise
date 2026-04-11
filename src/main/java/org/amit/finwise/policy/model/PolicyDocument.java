package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_documents", indexes = {
    @Index(name = "idx_policy_doc_authority", columnList = "authority"),
    @Index(name = "idx_policy_doc_area", columnList = "policy_area"),
    @Index(name = "idx_policy_doc_status", columnList = "status"),
    @Index(name = "idx_policy_doc_source_ref", columnList = "source_reference"),
    @Index(name = "idx_policy_doc_external_id", columnList = "external_document_id"),
    @Index(name = "idx_policy_doc_key", columnList = "document_key", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_key", nullable = false, unique = true, length = 200)
    private String documentKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private PolicyAuthority authority;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 50)
    private PolicyDocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "binding_level", nullable = false, length = 50)
    private PolicyBindingLevel bindingLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_area", nullable = false, length = 50)
    private PolicyArea policyArea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private PolicyDocumentStatus status = PolicyDocumentStatus.ACTIVE;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(name = "source_url", columnDefinition = "TEXT")
    private String sourceUrl;

    @Column(name = "source_reference", length = 300)
    private String sourceReference;

    @Column(name = "external_document_id", length = 200)
    private String externalDocumentId;

    @Column(name = "latest_version_number")
    @Builder.Default
    private Integer latestVersionNumber = 1;

    @Column(name = "published_date")
    private LocalDate publishedDate;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "tags_csv", length = 1000)
    private String tagsCsv;

    @Column(name = "affected_sectors_csv", length = 1000)
    private String affectedSectorsCsv;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
