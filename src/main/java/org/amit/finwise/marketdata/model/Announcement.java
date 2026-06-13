package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One NSE corporate announcement (DF-4) — the disclosure stream the brief reads
 * for events that aren't yet a structured corporate action: results, orders,
 * fund-raising, ratings, management changes. Keyed by a dedup hash over
 * (symbol | broadcast time | subject) so the date-range pull is idempotent.
 */
@Entity
@Table(name = "md_announcement",
        indexes = {
                @Index(name = "idx_md_ann_symbol_time", columnList = "symbol, broadcast_at DESC"),
                @Index(name = "idx_md_ann_instrument", columnList = "instrument_id"),
                @Index(name = "idx_md_ann_time", columnList = "broadcast_at DESC")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_ann_dedup", columnNames = {"dedup_hash"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id")
    private Long instrumentId;

    @Column(length = 30)
    private String symbol;

    @Column(name = "company_name", length = 200)
    private String companyName;

    /** NSE-assigned subject/category, e.g. "Financial Results", "Board Meeting". */
    @Column(length = 200)
    private String subject;

    @Column(length = 2000)
    private String details;

    /** Broadcast timestamp (IST) NSE stamps on the disclosure. */
    @Column(name = "broadcast_at")
    private LocalDateTime broadcastAt;

    /** Link to the attached PDF disclosure, when present. */
    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "dedup_hash", nullable = false, length = 64)
    private String dedupHash;

    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
