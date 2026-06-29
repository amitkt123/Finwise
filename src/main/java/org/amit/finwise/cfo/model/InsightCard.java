package org.amit.finwise.cfo.model;

import java.util.List;

/**
 * A typed, Java-rendered insight (Honest-Insights Phase B1) — the unit that replaces the
 * single prose dump in the CFO brief.
 *
 * <p>Guiding principle: <b>numbers are rendered in Java; the LLM only writes interpretation.</b>
 * Each card carries its computed figures as {@link Computation}s (value · method · inputs ·
 * window) plus caveats. The {@code narrative} is the only LLM-authored field, and an
 * {@code InsightNarrationService} validator guarantees it introduces no number absent from
 * {@code computations}. {@code calibratedConfidence}/{@code trackRecord} stay null until
 * Phase C wires the calibration scoreboard in.
 *
 * <p>Cards are generated on demand inside the existing brief cadence — no persistence.
 */
public record InsightCard(
        String id,                      // stable per-category id, e.g. "risk-budget-HDFCBANK"
        Category category,
        Severity severity,
        String title,                   // Java-rendered, e.g. "Trim HDFCBANK by ₹48,000"
        String actionVerb,              // trim/add/hold/watch/… (nullable; drives claim direction)
        String symbol,                  // nullable
        List<Computation> computations,
        List<String> caveats,
        double rawConfidence,
        Double calibratedConfidence,    // null until Phase C
        String trackRecord,             // null until Phase C
        String narrative                // null until B3 (LLM); may be stripped by the validator
) {
    public enum Category {
        RISK_BUDGET, CONCENTRATION, VOL_REGIME, FACTOR_TILT, SKILL,
        ATTRIBUTION, TAX, GOAL, LOOKTHROUGH, MARGINAL_ADD, VAR_BACKTEST, STRESS, BETA_DRIFT
    }

    /** INFO < WATCH < ACTION < ALERT — cards render highest-severity first. */
    public enum Severity { INFO, WATCH, ACTION, ALERT }

    /** The confidence to surface: calibrated when Phase C has populated it, else raw. */
    public double effectiveConfidence() {
        return calibratedConfidence != null ? calibratedConfidence : rawConfidence;
    }

    // ── Builder helpers ─────────────────────────────────────────────────────────
    // Lombok @Builder isn't applied to records; a small fluent builder keeps the
    // generators readable without per-field positional constructors.

    public static Builder builder(String id, Category category, Severity severity) {
        return new Builder(id, category, severity);
    }

    public static final class Builder {
        private final String id;
        private final Category category;
        private final Severity severity;
        private String title;
        private String actionVerb;
        private String symbol;
        private List<Computation> computations = List.of();
        private List<String> caveats = List.of();
        private double rawConfidence;
        private Double calibratedConfidence;
        private String trackRecord;
        private String narrative;

        private Builder(String id, Category category, Severity severity) {
            this.id = id;
            this.category = category;
            this.severity = severity;
        }

        public Builder title(String t) { this.title = t; return this; }
        public Builder actionVerb(String v) { this.actionVerb = v; return this; }
        public Builder symbol(String s) { this.symbol = s; return this; }
        public Builder computations(List<Computation> c) { this.computations = c; return this; }
        public Builder caveats(List<String> c) { this.caveats = c; return this; }
        public Builder rawConfidence(double c) { this.rawConfidence = c; return this; }
        public Builder calibratedConfidence(Double c) { this.calibratedConfidence = c; return this; }
        public Builder trackRecord(String t) { this.trackRecord = t; return this; }
        public Builder narrative(String n) { this.narrative = n; return this; }

        public InsightCard build() {
            return new InsightCard(id, category, severity, title, actionVerb, symbol,
                    computations == null ? List.of() : computations,
                    caveats == null ? List.of() : caveats,
                    rawConfidence, calibratedConfidence, trackRecord, narrative);
        }
    }
}
