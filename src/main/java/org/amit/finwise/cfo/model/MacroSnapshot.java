package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily snapshot of Indian macroeconomic state.
 *
 * Fields reflect the current state:
 *   - RBI repo rate (set via config)
 *   - Latest CPI YoY (set via config, refreshed monthly)
 *   - USD/INR exchange rate (from Yahoo)
 *   - India VIX (from NSE, intraday)
 *   - 10Y G-sec yield (set via config, used for Sharpe R_f)
 *
 * All fields are nullable; absence is acceptable (e.g. if an external feed is down).
 * Timestamps capture when data was last refreshed.
 */
@Entity
@Table(name = "cfo_macro_snapshot", indexes = {
    @Index(name = "idx_macro_snapshot_date", columnList = "snapshot_date DESC")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MacroSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    // ── RBI Policy Rates ──────────────────────────────────────────────────

    /** RBI repo rate (%) — benchmark for monetary policy */
    @Column(precision = 8, scale = 2)
    private BigDecimal repoRate;

    /** Date repo rate was last updated from RBI announcement */
    private LocalDate repoRateAsOf;

    // ── Inflation ────────────────────────────────────────────────────────

    /** Latest CPI YoY inflation (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal cpiYoY;

    /** Month/year the CPI figure is from (e.g., "Apr 2026") */
    @Column(length = 20)
    private String cpiAsOf;

    // ── Currency ─────────────────────────────────────────────────────────

    /** USD/INR exchange rate (INR per USD) */
    @Column(precision = 8, scale = 2)
    private BigDecimal usdInr;

    /** Timestamp of last USD/INR fetch */
    private LocalDateTime usdInrFetchedAt;

    // ── Volatility Index ─────────────────────────────────────────────────

    /** India VIX (NSE volatility index) */
    @Column(precision = 8, scale = 2)
    private BigDecimal indiaVix;

    /** Timestamp of last India VIX fetch */
    private LocalDateTime indiaVixFetchedAt;

    // ── Risk-Free Rate ──────────────────────────────────────────────────

    /** 3-month Government Security yield (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal gsec3m;

    /** 1-year Government Security yield (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal gsec1y;

    /** 5-year Government Security yield (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal gsec5y;

    /** 10-Year Government Security yield (%) — used as R_f in Sharpe/Sortino */
    @Column(precision = 8, scale = 2)
    private BigDecimal gsecYield10Y;

    /** Date G-sec yields were last updated */
    private LocalDate gsecYieldAsOf;

    /** Curve slope: 10Y - 3M yield differential (bps) — positive = normal, negative = inverted */
    @Column(precision = 8, scale = 2)
    private BigDecimal curveSlope10y3m;

    // ── Optional: Growth & Flows ─────────────────────────────────────────

    /** Latest GDP growth rate (%) */
    @Column(precision = 8, scale = 2)
    private BigDecimal gdpGrowth;

    /** Date GDP figure is from */
    private LocalDate gdpAsOf;

    /** FII net flow for the day (₹ Cr, negative = net selling). Source: NSE fiidiiTradeReact. */
    @Column(precision = 12, scale = 2)
    private BigDecimal fiiNetFlow;

    /** DII net flow for the day (₹ Cr, negative = net selling). Source: NSE fiidiiTradeReact. */
    @Column(precision = 12, scale = 2)
    private BigDecimal diiNetFlow;

    /**
     * 5-day cumulative FII net flow (₹ Cr) across the latest snapshots.
     * Below −10,000 Cr triggers the FLOW_PRESSURE macro regime.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal fiiNetFlow5d;

    /** Date FII/DII flows are from */
    private LocalDate flowsAsOf;

    // ── Data quality ────────────────────────────────────────────────────

    /** Notes on missing or stale data (e.g., "STALE:REPO_RATE (5 days)" ) */
    @Column(length = 500)
    private String dataQualityNotes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
