package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Market-wide daily OHLCV from NSE bhavcopy (every listed security, every
 * trading day). Bulk rows are written via JDBC batch upsert in
 * EodIngestionService, not through this entity — the entity exists for reads
 * and so Hibernate creates the table/constraints.
 */
@Entity
@Table(name = "md_eod_price",
        indexes = {
                @Index(name = "idx_md_eod_trade_date", columnList = "trade_date"),
                @Index(name = "idx_md_eod_symbol_date", columnList = "symbol, trade_date")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_eod_instrument_date",
                columnNames = {"instrument_id", "trade_date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EodPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id", nullable = false)
    private Long instrumentId;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    /** Symbol as of the trade date (instruments can be renamed; ISIN is the stable key). */
    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(precision = 14, scale = 2)
    private BigDecimal open;

    @Column(precision = 14, scale = 2)
    private BigDecimal high;

    @Column(precision = 14, scale = 2)
    private BigDecimal low;

    @Column(precision = 14, scale = 2)
    private BigDecimal close;

    @Column(precision = 14, scale = 2)
    private BigDecimal last;

    @Column(precision = 14, scale = 2)
    private BigDecimal prevClose;

    /**
     * Our own back-adjusted close: raw close × cumulative factor of all
     * corporate actions with ex_date after this trade_date. Populated by
     * PriceAdjustmentService; equals close for instruments with no actions.
     */
    @Column(name = "adj_close", precision = 18, scale = 6)
    private BigDecimal adjClose;

    private Long volume;

    @Column(precision = 18, scale = 2)
    private BigDecimal turnover;

    private Long trades;

    /** EQ / BE series at the time of the trade. */
    @Column(length = 4)
    private String series;

    /** Volume-weighted average price, from sec_bhavdata_full enrichment. */
    @Column(precision = 14, scale = 2)
    private BigDecimal vwap;

    /** Shares actually delivered (vs intraday churn), from sec_bhavdata_full. */
    @Column(name = "deliv_qty")
    private Long delivQty;

    /** Delivery percentage of traded quantity, from sec_bhavdata_full. */
    @Column(name = "deliv_pct", precision = 6, scale = 2)
    private BigDecimal delivPct;

    /** bhavcopy_cm (pre Jul-2024 format) | bhavcopy_udiff */
    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
