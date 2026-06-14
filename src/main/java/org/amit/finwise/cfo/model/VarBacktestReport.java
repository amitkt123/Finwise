package org.amit.finwise.cfo.model;

/**
 * Coverage test of a Value-at-Risk model against realized portfolio returns (Phase A).
 *
 * Produced by {@code VarBacktestService} via a rolling out-of-sample backtest: at each
 * day {@code t ≥ window} the VaR threshold is estimated from the trailing window and a
 * breach is recorded when the realized return is worse than {@code −VaR_t}. The counts
 * feed two likelihood-ratio tests:
 *
 * <ul>
 *   <li><b>Kupiec POF</b> (unconditional coverage) — does the breach rate match the
 *       nominal level {@code p}? {@code kupiecLR ~ χ²(1)}.</li>
 *   <li><b>Christoffersen independence</b> — are breaches serially independent, or do
 *       they cluster in volatile regimes? {@code christoffersenLR ~ χ²(1)}.</li>
 * </ul>
 *
 * A model that passes Kupiec but fails Christoffersen has the right average coverage but
 * mistimes risk; one that fails Kupiec with {@code actualBreachRate > expectedBreachRate}
 * understates tail risk.
 *
 * {@code method} names the VaR variant under test ("Parametric" | "Cornish-Fisher" |
 * "Historical") so the brief can state which number was validated.
 */
public record VarBacktestReport(
        String method,
        int window,
        double confidenceLevel,        // p, e.g. 0.05 or 0.01
        int observations,              // out-of-sample test days (T = length − window)
        int breaches,                  // x
        double expectedBreachRate,     // = p
        double actualBreachRate,       // = x / T
        double kupiecLR,
        double kupiecPValue,
        boolean kupiecReject,          // true → coverage rejected at 5%
        double christoffersenLR,
        double christoffersenPValue,
        boolean clusteringDetected,    // true → independence rejected at 5%
        String verdict
) {
    /** χ²(2) conditional-coverage statistic: LR_uc + LR_ind (joint coverage + independence). */
    public double conditionalCoverageLR() {
        return kupiecLR + christoffersenLR;
    }
}
