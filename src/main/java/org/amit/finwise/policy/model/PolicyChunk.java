package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "policy_chunks", indexes = {
    @Index(name = "idx_policy_chunk_version", columnList = "version_id"),
    @Index(name = "idx_policy_chunk_order", columnList = "version_id, chunk_index")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "version_id", nullable = false)
    private PolicyDocumentVersion version;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(length = 300)
    private String heading;

    @Column(name = "section_path", length = 500)
    private String sectionPath;

    @Column(name = "citation_label", length = 300)
    private String citationLabel;

    @Column(name = "page_number_start")
    private Integer pageNumberStart;

    @Column(name = "page_number_end")
    private Integer pageNumberEnd;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "keyword_text", columnDefinition = "TEXT")
    private String keywordText;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
