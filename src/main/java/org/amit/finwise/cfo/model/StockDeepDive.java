package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Evidence pack for a stock research query.
 *
 * All fields are computed in Java — the LLM receives this as structured context
 * and narrates; it does not calculate. Null / empty fields are surfaced as
 * DATA_INCOMPLETE so the LLM declares the gap instead of hallucinating.
 *
 * Phase 3 populates: live quote, technicals, news, portfolio fit.
 * Phases 4 and 5 will add: fundamentals, valuation z-scores, macro catalysts.
 */
public record StockDeepDive(

        String symbol,
        LocalDate asOf,

        // Live quote
        Double lastClose,
        Double dayChangePercent,
        boolean hitCircuitToday,
        String circuitType,          // "UPPER" / "LOWER" / null

        // Technical analysis (Phase 2)
        TechnicalSnapshot technicals, // null → INSUFFICIENT_HISTORY

        // Fundamentals & Valuation (Phase 4)
        StockFundamentals fundamentals, // null → INCOMPLETE:FUNDAMENTALS

        // Stock-specific news (last 7 days)
        List<NewsArticle> relevantNews,

        // Macro context (Phase 5)
        MacroSnapshot macroSnapshot, // null → INCOMPLETE:MACRO

        // Portfolio fit
        PortfolioFit portfolioFit,

        // Explicit risk and liquidity metrics computed from price history
        RiskMetrics riskMetrics,

        // Evidence-weighted investment scorecard
        StockScorecard scorecard,

        // Data completeness
        boolean knownSymbol,         // false → not in gazetteer
        boolean hasPriceHistory,     // false → no StockPriceHistory rows
        List<String> dataGaps        // human-readable list of missing fields

) {
    /**
     * Fit of this stock to the user's existing portfolio.
     *
     * If the stock is already held, weight/pctContribution come from Phase 1.
     * If not held, betaVsNifty is still computed from CovarianceEngine.
     * Phase 7 additions: marginal vol impact, correlation, verdict.
     */
    public record PortfolioFit(
            boolean isHeld,
            double weight,                  // market-value weight in portfolio (0 if not held)
            double betaVsNifty,             // β vs Nifty 50 (NaN if insufficient history)
            double pctContributionToRisk,   // %CTR from Phase 1 (NaN if not held)
            String sector,
            String fitSummary,              // one-line narrative
            // Phase 7:
            double correlationToPortfolio,  // ρ(candidate, existing portfolio) (NaN if insufficient)
            double marginalVolImpact,       // Δσ_p annualized (NaN if insufficient)
            double newPortfolioVol,         // σ_p after addition (NaN if insufficient)
            double hypotheticalWeight,      // always 0.05
            String verdictLabel,            // INITIATE/AVOID/WAIT-FOR-PULLBACK/NEEDS-MORE-DATA
            String verdictRationale         // one-line explanation
    ) {}

    public record RiskMetrics(
            double maxDrawdown,             // negative number, e.g. -0.25 means -25%
            double downsideBetaVsNifty,     // beta on benchmark-down days
            double ewmaVolatilityAnn,       // annualized decimal volatility
            double stressedCorrelation,     // correlation during worst benchmark-return days
            double averageTradedValue,      // close * volume average over available history
            int observationCount
    ) {
        public static RiskMetrics unavailable() {
            return new RiskMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);
        }
    }
}
