package org.amit.finwise.marketdata.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Master record for an AMFI-registered mutual-fund scheme (DF-4), keyed on the
 * AMFI scheme code that appears in {@code NAVAll.txt}. The ISIN (when AMFI
 * publishes one) bridges to {@link org.amit.finwise.cfo.model.MfPortfolioHolding}
 * disclosures and to the user's holdings; category/AMC drive the history-seed
 * tiering (equity/hybrid first, the full ~40k universe later).
 */
@Entity
@Table(name = "md_mf_scheme",
        indexes = {
                @Index(name = "idx_md_mf_scheme_isin", columnList = "isin"),
                @Index(name = "idx_md_mf_scheme_category", columnList = "category")
        },
        uniqueConstraints = {@UniqueConstraint(name = "uq_md_mf_scheme_amfi_code",
                columnNames = {"amfi_code"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfScheme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AMFI scheme code — the join key to NAV rows and NAVAll.txt. */
    @Column(name = "amfi_code", nullable = false, length = 12)
    private String amfiCode;

    /** Payout/reinvestment ISIN, when AMFI publishes one for the scheme. */
    @Column(length = 20)
    private String isin;

    /** Secondary (reinvestment) ISIN — AMFI lists two on many growth/IDCW pairs. */
    @Column(name = "isin_reinvest", length = 20)
    private String isinReinvest;

    @Column(name = "scheme_name", nullable = false, length = 300)
    private String schemeName;

    /** AMC / fund-house name, parsed from the NAVAll.txt section header. */
    @Column(length = 200)
    private String amc;

    /** AMFI scheme-type header, e.g. "Open Ended Schemes(Equity Scheme - Large Cap Fund)". */
    @Column(length = 200)
    private String category;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
