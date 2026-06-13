package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A deduplicated event in the market's news memory (DF-6).
 *
 * Many RSS sources report the same event; embedding-similarity dedup at ingest
 * (cosine >= ~0.92 against the last 72h) attaches near-duplicate articles to one
 * cluster instead of inflating sentiment and wasting LLM refinement. The cluster
 * — not the individual article — is what the outcome-enrichment job scores and
 * what the evidence-pack retriever returns.
 *
 * The cluster centroid is intentionally NOT stored here: dedup compares against
 * per-article embeddings in {@code cfo_news_embeddings} (strictly more accurate
 * than a drifting running centroid), and retrieval matches the canonical
 * article's embedding. The cluster is the relational anchor for events/outcomes.
 */
@Entity
@Table(name = "cfo_news_cluster",
        indexes = {
                @Index(name = "idx_news_cluster_first_seen", columnList = "first_seen DESC"),
                @Index(name = "idx_news_cluster_canonical", columnList = "canonical_article_id")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The first (representative) article that opened this cluster. */
    @Column(name = "canonical_article_id", nullable = false)
    private Long canonicalArticleId;

    @Column(columnDefinition = "TEXT")
    private String title;

    /** When the event first entered the corpus — the event date for outcome scoring. */
    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    /** Number of articles (sources) that reported this event. */
    @Column(name = "article_count")
    @Builder.Default
    private Integer articleCount = 1;
}
