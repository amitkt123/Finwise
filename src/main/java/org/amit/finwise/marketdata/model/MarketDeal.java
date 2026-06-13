package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One bulk- or block-deal line (DF-4): a large reported trade in a stock with
 * the counterparty (client) name, side, quantity and price. {@code dealType}
 * distinguishes BULK from BLOCK. Keyed by a dedup hash over the deal's natural
 * identity so the daily/range pull is idempotent.
 */
@Entity
@Table(name = "md_market_deal",
        indexes = {
                @Index(name = "idx_md_deal_symbol_date", columnList = "symbol, deal_date DESC"),
                @Index(name = "idx_md_deal_instrument", columnList = "instrument_id"),
                @Index(name = "idx_md_deal_date_type", columnList = "deal_date DESC, deal_type")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_deal_dedup", columnNames = {"dedup_hash"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDeal {

    public enum DealType { BULK, BLOCK }

    public enum Side { BUY, SELL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instrument_id")
    private Long instrumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "deal_type", nullable = false, length = 6)
    private DealType dealType;

    @Column(name = "deal_date", nullable = false)
    private LocalDate dealDate;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(name = "security_name", length = 200)
    private String securityName;

    @Column(name = "client_name", length = 300)
    private String clientName;

    @Enumerated(EnumType.STRING)
    @Column(length = 4)
    private Side side;

    @Column
    private Long quantity;

    @Column(precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "dedup_hash", nullable = false, length = 64)
    private String dedupHash;

    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
