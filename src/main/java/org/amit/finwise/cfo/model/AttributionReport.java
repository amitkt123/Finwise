package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Brinson-Fachler performance attribution (Phase 12b).
 *
 * Decomposes the portfolio's excess return over Nifty into allocation, selection, and
 * interaction effects per sector, per monthly bucket, then geometrically links the
 * buckets (Cariño smoothing) so the effects reconcile to the compounded excess return.
 * The {@code residual} is the (tiny) gap left after linking and is reported explicitly
 * rather than absorbed.
 *
 * Single-bucket identities (weights summing to 1 each):
 *   A_s = (w_p,s − w_b,s)(r_b,s − r_b)        allocation
 *   S_s = w_b,s (r_p,s − r_b,s)               selection
 *   I_s = (w_p,s − w_b,s)(r_p,s − r_b,s)      interaction
 *   Σ_s (A_s + S_s + I_s) = r_p − r_b
 *
 * Benchmark sector weights come from {@code data/nifty_sector_weights.csv} (manual
 * quarterly refresh); a disclosure older than 120 days sets {@code lowConfidence}.
 * Benchmark sector returns r_b,s come from the P8 NSE sector-index series.
 */
public record AttributionReport(
        LocalDate benchmarkAsOf,
        LocalDate windowStart,
        LocalDate windowEnd,
        int buckets,

        double portfolioReturn,        // compounded r_p over the window
        double benchmarkReturn,        // compounded r_b over the window
        double excessReturn,           // r_p − r_b (geometric)

        double allocationEffect,       // linked Σ A_s
        double selectionEffect,        // linked Σ S_s
        double interactionEffect,      // linked Σ I_s
        double residual,               // (allocation+selection+interaction) − excessReturn

        List<SectorAttribution> sectors,

        boolean lowConfidence,
        List<String> dataQualityNotes,
        String headline
) {
    /** Linked effects for one sector, summed across buckets. */
    public record SectorAttribution(
            String sector,
            double allocation,
            double selection,
            double interaction,
            double total
    ) {}
}
