package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One NSE corporate action (split / bonus / rights / dividend / other) keyed by
 * ISIN. Bulk rows are written via JDBC batch upsert in CorporateActionService;
 * the entity exists for reads and so Hibernate creates the table.
 *
 * Idempotency: dedupHash = sha256(isin | exDate | subject) carries the unique
 * constraint, sidestepping null ex-dates and long subject lines.
 */
@Entity
@Table(name = "md_corporate_action",
        indexes = {
                @Index(name = "idx_md_ca_symbol_exdate", columnList = "symbol, ex_date"),
                @Index(name = "idx_md_ca_instrument", columnList = "instrument_id"),
                @Index(name = "idx_md_ca_exdate", columnList = "ex_date")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_ca_dedup", columnNames = {"dedup_hash"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CorporateAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Resolved from the marketdata instrument master by ISIN; null if outside our universe. */
    @Column(name = "instrument_id")
    private Long instrumentId;

    @Column(nullable = false, length = 12)
    private String isin;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Column(length = 4)
    private String series;

    @Column(name = "company_name", length = 160)
    private String companyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 12)
    private CorporateActionType actionType;

    /** Raw NSE subject line, e.g. "Bonus 1:2" or "Interim Dividend - Rs 9 Per Share". */
    @Column(nullable = false, length = 400)
    private String subject;

    @Column(name = "ex_date")
    private LocalDate exDate;

    @Column(name = "record_date")
    private LocalDate recordDate;

    /** Ratio numerator (new shares) for bonus/rights; split ignores this. */
    @Column(name = "ratio_new")
    private Integer ratioNew;

    /** Ratio denominator (held shares) for bonus/rights. */
    @Column(name = "ratio_old")
    private Integer ratioOld;

    /** Split: face value before the split. */
    @Column(name = "from_face_value", precision = 10, scale = 2)
    private BigDecimal fromFaceValue;

    /** Split: face value after the split. */
    @Column(name = "to_face_value", precision = 10, scale = 2)
    private BigDecimal toFaceValue;

    /** Dividend: total per-share amount (sums interim + special when stated together). */
    @Column(name = "dividend_amount", precision = 12, scale = 4)
    private BigDecimal dividendAmount;

    @Column(name = "dedup_hash", nullable = false, length = 64)
    private String dedupHash;

    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
