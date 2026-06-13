package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A structured market event distilled from a news cluster (DF-6). Sits over the
 * cluster so the corpus reads as an event memory ("RBI cut repo on 2024-06-07")
 * rather than a pile of articles. Designed to union with the DF-2 corporate-event
 * tables (type + event_date + structured payload).
 */
@Entity
@Table(name = "cfo_market_event",
        indexes = {
                @Index(name = "idx_market_event_date", columnList = "event_date DESC"),
                @Index(name = "idx_market_event_cluster", columnList = "cluster_id")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_market_event_cluster",
                columnNames = {"cluster_id"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketEvent {

    public enum EventType {
        RATE_DECISION, RESULTS, REGULATORY, MNA, GUIDANCE, MACRO_PRINT, IPO, OTHER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cluster_id", nullable = false)
    private Long clusterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private EventType eventType;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /** Free-form structured detail (e.g. {"repo_bps":-25}); jsonb in Postgres. */
    @Column(name = "structured_payload", columnDefinition = "jsonb")
    private String structuredPayload;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
