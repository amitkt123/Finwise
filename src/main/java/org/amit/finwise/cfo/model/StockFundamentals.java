package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily-refreshed fundamentals snapshot for a stock.
 * Sourced from Yahoo Finance quoteSummary endpoint.
 *
 * Valuation z-scores are computed against the stock's own 2-year history:
 *   z = (current - mean) / stdev → labels CHEAP / FAIR / EXPENSIVE
 *
 * Any null margin/earnings → DATA_INCOMPLETE flag so LLM declares the gap.
 */
@Entity
@Table(name = "cfo_stock_fundamentals", indexes = {
    @Index(name = "idx_fundamentals_symbol_date", columnList = "symbol, snapshot_date DESC")
},
uniqueConstraints = {
    @UniqueConstraint(name = "uq_fundamentals_symbol_date", columnNames = {"symbol", "snapshot_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockFundamentals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    // ── Valuation multiples ──────────────────────────────────────────────

    /** P/E ratio (trailing) */
    @Column(precision = 10, scale = 2)
    private BigDecimal trailingPE;

    /** P/E ratio (forward) */
    @Column(precision = 10, scale = 2)
    private BigDecimal forwardPE;

    /** Price-to-Book ratio */
    @Column(precision = 10, scale = 2)
    private BigDecimal priceToBook;

    /** EV/EBITDA multiple */
    @Column(precision = 10, scale = 2)
    private BigDecimal evToEbitda;

    /** EV/Sales multiple */
    @Column(precision = 10, scale = 2)
    private BigDecimal evToSales;

    /** PEG ratio (P/E divided by expected earnings growth) */
    @Column(precision = 10, scale = 2)
    private BigDecimal pegRatio;

    // ── Profitability & margins ──────────────────────────────────────────

    /** Dividend yield (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal dividendYield;

    /** Revenue growth (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal revenueGrowth;

    /** Gross profit margin (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal grossMargin;

    /** EBITDA margin (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal ebitdaMargin;

    /** Operating margin (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal operatingMargin;

    /** Net profit margin (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal netProfitMargin;

    /** Return on Equity (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal roe;

    /** Debt-to-Equity ratio */
    @Column(precision = 10, scale = 2)
    private BigDecimal debtToEquity;

    /** Free Cash Flow (INR, in thousands for scale) */
    @Column(precision = 16, scale = 0)
    private BigDecimal freeCashFlow;

    // ── Quality signals ──────────────────────────────────────────────────

    /** True if the stock is profitable (net margin > 0) */
    private Boolean isProfitable;

    // ── Valuation z-score (computed against 2yr history) ──────────────────

    /** Z-score of current P/E vs 2-year history (null if insufficient history) */
    @Column(precision = 8, scale = 2)
    private BigDecimal peZScore;

    /** Z-score of current EV/EBITDA vs 2-year history */
    @Column(precision = 8, scale = 2)
    private BigDecimal evEbitdaZScore;

    /** Valuation label: CHEAP / FAIR / EXPENSIVE (based on z-score: <-1, -1 to 1, >1) */
    @Column(length = 20)
    private String valuationLabel;

    // ── Data quality ────────────────────────────────────────────────────

    /** Flags missing critical fields (e.g. INCOMPLETE:MARGINS, INCOMPLETE:EARNINGS) */
    @Column(length = 500)
    private String dataQualityNotes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
