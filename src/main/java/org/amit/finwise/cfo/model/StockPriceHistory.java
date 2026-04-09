package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_stock_price_history", indexes = {
    @Index(name = "idx_price_history_symbol_date", columnList = "symbol, price_date DESC")
},
uniqueConstraints = {
    @UniqueConstraint(name = "uq_price_symbol_date", columnNames = {"symbol", "price_date"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockPriceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(nullable = false)
    private LocalDate priceDate;

    @Column(precision = 12, scale = 2)
    private BigDecimal openPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal highPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal lowPrice;

    @Column(precision = 12, scale = 2)
    private BigDecimal closePrice;

    private Long volume;

    /** Day-over-day percentage change in close price */
    private Double priceChangePercent;

    // ── Circuit breaker tracking ──────────────────────────────────────────────

    /** True if stock hit upper circuit limit on this day */
    @Builder.Default
    private Boolean hitUpperCircuit = false;

    /** True if stock hit lower circuit limit on this day */
    @Builder.Default
    private Boolean hitLowerCircuit = false;

    /**
     * Number of consecutive days the stock has been hitting the same circuit
     * (upper or lower) as of this date. Used for anomaly detection.
     * >= 2 consecutive circuits → anomalyAlert flag in PersonalizedNewsItem.
     */
    @Builder.Default
    private Integer consecutiveCircuitCount = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
