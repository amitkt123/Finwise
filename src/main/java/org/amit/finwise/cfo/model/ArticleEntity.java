package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A typed edge from an article to a market entity (DF-6). This relational table
 * <em>is</em> the knowledge graph — kept in Postgres rather than a graph DB.
 *
 * Edges come from two sources: the gazetteer/Tier-2 extraction (high precision,
 * symbol/sector matches) and the LLM refinement (lower precision, gated at
 * confidence >= 0.6). Outcome enrichment walks the INSTRUMENT edges of a
 * cluster to find which listed names to score against realized returns.
 */
@Entity
@Table(name = "cfo_article_entity",
        indexes = {
                @Index(name = "idx_article_entity_article", columnList = "article_id"),
                @Index(name = "idx_article_entity_entity", columnList = "entity_type, entity_id")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_article_entity",
                columnNames = {"article_id", "entity_type", "entity_id", "source"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ArticleEntity {

    public enum EntityType { INSTRUMENT, SECTOR, INDEX, MACRO_THEME, POLICY }

    public enum Source { GAZETTEER, LLM }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 16)
    private EntityType entityType;

    /** NSE symbol (INSTRUMENT), sector name (SECTOR), index name, or theme key. */
    @Column(name = "entity_id", nullable = false, length = 100)
    private String entityId;

    private double confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Source source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
