package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One constituent line of a mutual-fund portfolio disclosure (Phase 12a).
 *
 * AMFI exposes no portfolio API and per-AMC monthly factsheets vary in format, so the
 * ingestion contract is a manual CSV import (see {@code MfPortfolioImportService}). Each
 * row is the fund's holding of one underlying stock at a disclosure date:
 *
 *   schemeCode , asOf , isin , symbol , weightPct , sector
 *
 * Composition is a property of the FUND, not of any user — rows are keyed by
 * {@code schemeCode + asOf} and shared across every investor holding that scheme.
 * {@code asOf} is always stamped because disclosures are monthly at best and staleness
 * matters to the look-through confidence gate.
 */
@Entity
@Table(name = "cfo_mf_portfolio_holdings", indexes = {
        @Index(name = "idx_mfph_scheme_asof", columnList = "scheme_code, as_of"),
        @Index(name = "idx_mfph_isin", columnList = "isin"),
        @Index(name = "idx_mfph_symbol", columnList = "symbol")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MfPortfolioHolding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AMFI scheme code (preferred) or an AMC-assigned identifier for the fund. */
    @Column(name = "scheme_code", nullable = false, length = 40)
    private String schemeCode;

    /** Disclosure date of this portfolio snapshot (month-end at best). */
    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    /** ISIN of the underlying constituent, when the factsheet provides it. */
    @Column(name = "isin", length = 20)
    private String isin;

    /** NSE symbol of the underlying constituent (uppercase), when resolvable. */
    @Column(name = "symbol", length = 50)
    private String symbol;

    /** Weight of this constituent within the fund, as a percentage (0–100). */
    @Column(name = "weight_pct", nullable = false)
    private Double weightPct;

    /** Gazetteer/factsheet sector of the constituent, when provided. */
    @Column(name = "sector", length = 100)
    private String sector;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
