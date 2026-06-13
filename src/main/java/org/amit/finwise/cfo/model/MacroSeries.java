package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Generic macroeconomic time series — one row per (series_code, obs_date)
 * observation (DF-3). Replaces the hand-typed {@code cfo.macro.*} config
 * constants with real, historically-seeded data from FBIL / RBI / MOSPI and
 * DF-1's own index feeds.
 *
 * <p>{@link MacroStateService} and {@code YieldCurveService} read the latest /
 * as-of observation per code; staleness is derived from {@code obsDate} against
 * each code's SLA ({@link MacroSeriesCode#slaMaxAgeDays()}), so the old "update
 * the config" nags become per-series freshness checks.
 */
@Entity
@Table(name = "cfo_macro_series",
        indexes = {@Index(name = "idx_macro_series_code_date", columnList = "series_code, obs_date DESC")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_macro_series_code_date",
                columnNames = {"series_code", "obs_date"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MacroSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "series_code", nullable = false, length = 24)
    private MacroSeriesCode seriesCode;

    /** The date the observation refers to (trade date for dailies, month-end for monthlies). */
    @Column(name = "obs_date", nullable = false)
    private LocalDate obsDate;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal value;

    /** Origin of the datum: FBIL, RBI, MOSPI, NSE_INDEX, … */
    @Column(length = 20)
    private String source;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}
