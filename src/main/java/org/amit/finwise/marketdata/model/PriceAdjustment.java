package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One price back-adjustment event for an instrument: the multiplicative factor
 * applied to every close strictly BEFORE its ex-date. md_eod_price.adj_close is
 * close × (product of all factors with ex_date > trade_date), recomputed by
 * PriceAdjustmentService whenever a new corporate action lands.
 */
@Entity
@Table(name = "md_price_adjustment",
        indexes = {@Index(name = "idx_md_padj_instrument", columnList = "instrument_id")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_padj_event",
                columnNames = {"instrument_id", "ex_date", "action_type"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(name = "ex_date", nullable = false)
    private LocalDate exDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 12)
    private CorporateActionType actionType;

    /** Price multiplier for closes before ex-date: split toFV/fromFV, bonus b/(a+b), dividend 1-(div/prevClose). */
    @Column(name = "factor", nullable = false, precision = 18, scale = 10)
    private BigDecimal factor;

    @Column(length = 200)
    private String description;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
