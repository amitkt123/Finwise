package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DF-7 — one actionable, directional claim extracted from a generated brief
 * (e.g. "TCS, bullish, 5d, confidence 0.7"). The nightly evaluation job scores
 * each matured claim against the instrument's realized <em>excess</em> return vs
 * Nifty from {@code md_eod_price}, producing a calibration scoreboard per
 * provider/prompt-version. No prompt tuning happens without this scoreboard.
 *
 * @see org.amit.finwise.cfo.service.InsightEvaluationService
 */
@Entity
@Table(name = "cfo_insight_claim",
        indexes = {
                @Index(name = "idx_claim_insight", columnList = "insight_id"),
                @Index(name = "idx_claim_scored", columnList = "scored"),
                @Index(name = "idx_claim_provider_version", columnList = "provider, prompt_version")},
        uniqueConstraints = {@UniqueConstraint(name = "uq_claim_insight_symbol_horizon",
                columnNames = {"insight_id", "symbol", "horizon"})})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsightClaim {

    /** Directional stance the brief took on the symbol. */
    public enum Direction { BULLISH, BEARISH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "insight_id", nullable = false)
    private Long insightId;

    @Column(nullable = false)
    private String userId;

    /** The brief's date — the baseline trading day (t0) for the realized return. */
    @Column(name = "insight_date", nullable = false)
    private LocalDate insightDate;

    @Column(nullable = false, length = 30)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Direction direction;

    /** Trading-day horizon over which the claim is judged. Reuses DF-6 horizons. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EventOutcome.Horizon horizon;

    /** Self-reported confidence (0.0–1.0) parsed from the brief. */
    @Column(nullable = false)
    private Double confidence;

    /**
     * Optional self-reported expected holding-period return for the horizon
     * (e.g. 0.08 = +8%), parsed from the BPR-3 structured claims block. Null for
     * regex-extracted claims that state only a direction. Scored for magnitude
     * calibration by the nightly job once the horizon matures (BPR-10).
     */
    @Column(name = "expected_return")
    private Double expectedReturn;

    /** Free-text thesis from the structured block; null for regex-extracted claims. */
    @Column(name = "thesis", length = 500)
    private String thesis;

    /** Brief provider (claude/openai/ollama/…) — a scoreboard dimension. */
    @Column(length = 20)
    private String provider;

    /** Prompt version — the other scoreboard dimension; bump when the prompt changes. */
    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    // ── Scoring (filled by the nightly job once the horizon matures) ──────────

    @Builder.Default
    private boolean scored = false;

    /** Back-adjusted price return of the symbol over the horizon. */
    @Column(name = "raw_return")
    private Double rawReturn;

    /** Return minus the Nifty return over the same window — what we judge on. */
    @Column(name = "excess_return_vs_nifty")
    private Double excessReturnVsNifty;

    /** True when {@code sign(excessReturn)} agreed with the claimed direction. */
    private Boolean correct;

    @Column(name = "scored_at")
    private LocalDateTime scoredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
