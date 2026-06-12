package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Mutual-fund look-through exposure (Phase 12a).
 *
 * Resolves the portfolio's TRUE per-stock and per-sector exposure by exploding each
 * fund into its disclosed constituents:
 *
 *   effectiveWeight(stock) = directWeight(stock) + Σ_f w_f · h_{f,stock}
 *
 * where {@code w_f} is the fund's weight in the portfolio and {@code h_{f,stock}} the
 * stock's weight inside fund f. The portion of fund money whose constituents are not
 * disclosed (or unresolvable to a symbol) is carried as {@code unmappedResidue} rather
 * than silently dropped.
 *
 * The effective HHIs and P8 factor exposures are only trusted to consume these numbers
 * when {@code feedsModel} is true (MF coverage ≥ 70%); below that the look-through is
 * note-only. Every fund's disclosure date is stamped in {@code asOfByScheme} because
 * factsheets are monthly at best.
 */
public record LookThroughResult(
        Map<String, LocalDate> asOfByScheme,     // schemeCode → disclosure date used

        Map<String, Double> directWeights,       // symbol → directly-held weight (fraction)
        Map<String, Double> fundContributed,     // symbol → weight contributed via funds
        Map<String, Double> effectiveWeights,    // symbol → direct + fund-contributed
        Map<String, Double> sectorEffective,     // sector → effective weight

        double mfWeight,                          // total portfolio weight in mutual funds
        double unmappedResidue,                   // MF weight whose constituents are unseen
        double mfCoverage,                        // seen-through MF weight ÷ total MF weight

        double directNameHHI,                     // Σ w_i² before look-through
        double effectiveNameHHI,                  // Σ w_i² after look-through
        double directSectorHHI,
        double effectiveSectorHHI,

        boolean feedsModel,                       // mfCoverage ≥ 0.70
        List<String> dataQualityNotes,
        String headline
) {
}
