package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_portfolio_snapshots", indexes = {
    @Index(name = "idx_snapshot_user_time", columnList = "user_id, snapshot_time DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** When this snapshot was taken */
    @Column(nullable = false)
    private LocalDateTime snapshotTime;

    /** Source: GROWW | ZERODHA | MANUAL */
    @Column(nullable = false)
    private String source;

    /** Total invested cost */
    private BigDecimal totalInvested;

    /** Current market value */
    private BigDecimal currentValue;

    /** Unrealized P&L */
    private BigDecimal unrealizedPnl;

    /** Day's P&L */
    private BigDecimal dayPnl;

    /** Day's P&L % */
    private BigDecimal dayPnlPercent;

    /** Overall gain/loss % */
    private BigDecimal overallPnlPercent;

    /** Number of holdings */
    private Integer holdingsCount;

    /** Full JSON snapshot from Groww API response */
    @Column(columnDefinition = "TEXT")
    private String rawSnapshotJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
