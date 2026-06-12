package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Quarterly financial snapshot for a stock.
 * Tracks 8+ quarters of revenue, earnings, cash flow, and balance sheet metrics.
 * Populated from Yahoo quoteSummary earnings/balance sheet/cashflow modules.
 */
@Entity
@Table(name = "cfo_quarterly_fundamentals", indexes = {
        @Index(name = "idx_quarterly_symbol_date", columnList = "symbol, quarter_end DESC")
},
uniqueConstraints = {
        @UniqueConstraint(name = "uq_quarterly_symbol_quarter", columnNames = {"symbol", "quarter_end"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuarterlyFundamentals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private LocalDate quarterEnd;

    // ── Income Statement ───────────────────────────────────────────────────

    @Column(precision = 18, scale = 2)
    private BigDecimal revenue;

    @Column(precision = 18, scale = 2)
    private BigDecimal netIncome;

    @Column(precision = 10, scale = 2)
    private BigDecimal eps;

    // ── Balance Sheet ──────────────────────────────────────────────────────

    @Column(precision = 18, scale = 2)
    private BigDecimal totalAssets;

    @Column(precision = 18, scale = 2)
    private BigDecimal totalLiabilities;

    @Column(precision = 12, scale = 0)
    private BigDecimal sharesOutstanding;

    // ── Cash Flow ──────────────────────────────────────────────────────────

    @Column(precision = 18, scale = 2)
    private BigDecimal operatingCashFlow;

    // ── Derived Margins ────────────────────────────────────────────────────

    @Column(precision = 8, scale = 2)
    private BigDecimal grossMargin;  // (gross profit / revenue) * 100

    // ── Data Quality ───────────────────────────────────────────────────────

    /**
     * How confident are we in the trend (needs ≥6 quarters for HIGH).
     * LOW: < 6 quarters, MEDIUM: 6-8 quarters, HIGH: ≥9 quarters
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private TrendConfidence trendConfidence = TrendConfidence.LOW;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum TrendConfidence {
        LOW,      // < 6 quarters
        MEDIUM,   // 6-8 quarters
        HIGH      // ≥ 9 quarters
    }
}
