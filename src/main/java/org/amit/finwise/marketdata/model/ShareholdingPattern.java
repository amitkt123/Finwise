package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Quarterly shareholding pattern for one stock (DF-4) — among the highest-signal
 * Indian datasets: promoter holding, promoter pledge, FII and DII stakes. Keyed
 * on (symbol, period_ended) so the quarterly pull is an idempotent upsert; the
 * brief reads promoter-pledge spikes and FII/DII rotation per name.
 */
@Entity
@Table(name = "md_shareholding_pattern",
        indexes = {
                @Index(name = "idx_md_shp_symbol_period", columnList = "symbol, period_ended DESC"),
                @Index(name = "idx_md_shp_instrument", columnList = "instrument_id")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_shp_symbol_period",
                columnNames = {"symbol", "period_ended"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShareholdingPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id")
    private Long instrumentId;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "company_name", length = 200)
    private String companyName;

    /** Quarter-end the disclosure covers (e.g. 2026-03-31). */
    @Column(name = "period_ended", nullable = false)
    private LocalDate periodEnded;

    @Column(name = "promoter_pct", precision = 8, scale = 4)
    private BigDecimal promoterPct;

    /** Pledged promoter shares as a % of promoter holding (the risk signal). */
    @Column(name = "promoter_pledge_pct", precision = 8, scale = 4)
    private BigDecimal promoterPledgePct;

    @Column(name = "fii_pct", precision = 8, scale = 4)
    private BigDecimal fiiPct;

    @Column(name = "dii_pct", precision = 8, scale = 4)
    private BigDecimal diiPct;

    @Column(name = "public_pct", precision = 8, scale = 4)
    private BigDecimal publicPct;

    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
