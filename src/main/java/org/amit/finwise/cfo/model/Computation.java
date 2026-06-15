package org.amit.finwise.cfo.model;

/**
 * One Java-rendered number on an {@link InsightCard} (Honest-Insights Phase B1).
 *
 * <p>Every metric that reaches the user carries its full provenance so the figure can be
 * audited and the LLM can never become the source of truth for a number:
 * <ul>
 *   <li>{@code label}  — what the number is ("1-Day VaR 95% (Cornish-Fisher)").</li>
 *   <li>{@code value}  — the formatted figure, produced in Java via {@code String.format}
 *       (e.g. "₹48,000", "12.4%", "1.16"). This string is rendered byte-for-byte.</li>
 *   <li>{@code method} — how it was computed ("z·σ·V, Cornish-Fisher z").</li>
 *   <li>{@code inputs} — the data it was computed from ("daily value-weighted returns").</li>
 *   <li>{@code window} — the estimation window ("2023-06-01 → 2026-06-13, 740d").</li>
 * </ul>
 *
 * {@code method}/{@code inputs}/{@code window} are nullable when not meaningful for a metric.
 */
public record Computation(
        String label,
        String value,
        String method,
        String inputs,
        String window
) {
    /** Convenience for metrics whose method/inputs/window are not separately meaningful. */
    public static Computation of(String label, String value) {
        return new Computation(label, value, null, null, null);
    }
}
