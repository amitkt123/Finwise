package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily close for every NSE index from ind_close_all_DDMMYYYY.csv —
 * includes the index P/E, P/B and dividend yield published by NSE, giving
 * real multi-year valuation history (fixes the z-score cold start).
 */
@Entity
@Table(name = "md_index_eod",
        indexes = {@Index(name = "idx_md_index_eod_name_date", columnList = "index_name, trade_date DESC")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_index_eod_name_date",
                columnNames = {"index_name", "trade_date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexEod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "index_name", nullable = false, length = 80)
    private String indexName;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal open;

    @Column(precision = 14, scale = 2)
    private BigDecimal high;

    @Column(precision = 14, scale = 2)
    private BigDecimal low;

    @Column(precision = 14, scale = 2)
    private BigDecimal close;

    @Column(precision = 14, scale = 2)
    private BigDecimal pointsChange;

    @Column(precision = 8, scale = 2)
    private BigDecimal changePct;

    private Long volume;

    /** Turnover in Rs. crore as published. */
    @Column(precision = 18, scale = 2)
    private BigDecimal turnoverCr;

    @Column(precision = 10, scale = 2)
    private BigDecimal pe;

    @Column(precision = 10, scale = 2)
    private BigDecimal pb;

    @Column(precision = 8, scale = 2)
    private BigDecimal divYield;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
