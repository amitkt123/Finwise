package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Event-shaped market calendar entry (results, board meetings, ex-dates) for
 * both the forward NSE event calendar and the historical earnings import.
 * Designed so DF-6's market_event can union over it without remodeling: a
 * type, an event_date, and a structured JSONB payload.
 *
 * instrumentId is nullable — events arrive keyed only by symbol and may be for
 * securities outside the bhavcopy universe (SME, newly listed).
 */
@Entity
@Table(name = "md_corporate_event",
        indexes = {
                @Index(name = "idx_md_event_symbol_date", columnList = "symbol, event_date"),
                @Index(name = "idx_md_event_date", columnList = "event_date"),
                @Index(name = "idx_md_event_type", columnList = "event_type")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_event_dedup", columnNames = {"dedup_hash"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorporateEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id")
    private Long instrumentId;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "company_name", length = 160)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private CorporateEventType eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /** Free-text purpose/description as published (e.g. "Q4 2024", "To consider Fund Raising"). */
    @Column(length = 500)
    private String description;

    /** Structured source-specific fields preserved verbatim for downstream re-mining. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "dedup_hash", nullable = false, length = 64)
    private String dedupHash;

    /** event_calendar (forward NSE) | earnings_history (one-off JSON import). */
    @Column(length = 24)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
