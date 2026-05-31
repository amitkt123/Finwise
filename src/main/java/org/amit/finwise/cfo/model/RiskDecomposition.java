package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of all computed portfolio risk metrics for one user.
 * All volatility and deviation figures are annualized (×√252) unless noted.
 * All ₹ figures use the portfolio's current market value as the base.
 */
public record RiskDecomposition(

        // ── Data quality ─────────────────────────────────────────────────────────
        List<String> includedSymbols,
        List<String> excludedSymbols,   // insufficient price history (< MIN_OBSERVATIONS)
        int observationCount,           // aligned trading-day count used in covariance
        LocalDate seriesFrom,
        LocalDate seriesTo,
        boolean isLowConfidence,

        // ── Volatility ───────────────────────────────────────────────────────────
        double annualizedVolatility,    // σ_p × √252
        double dailyVolatility,         // σ_p = √(w^T Σ w)

        // ── Beta ─────────────────────────────────────────────────────────────────
        double portfolioBeta,           // Σ wᵢ βᵢ vs Nifty 50
        Map<String, Double> perHoldingBeta,

        // ── VaR / CVaR (₹ loss, positive = loss) ────────────────────────────────
        double var95Parametric,         // 1.645 × σ_p,daily × V
        double var99Parametric,         // 2.326 × σ_p,daily × V
        double var95Historical,         // empirical 5th-percentile × V
        double cvar95,                  // expected shortfall beyond VaR95 × V

        // ── Risk contributions ───────────────────────────────────────────────────
        List<RiskContributor> riskContributors,  // sorted by percentContributionToRisk DESC

        // ── Diversification ──────────────────────────────────────────────────────
        double diversificationRatio,    // (Σ wᵢ σᵢ) / σ_p; 1 = no benefit
        double effectiveNumberOfBets,   // 1 / Σ pᵢ² — risk-concentration HHI inverse
        double nameHHI,                 // Σ wᵢ² on market-value weights
        double sectorHHI,               // Σ (sector_w)²

        // ── Return-risk metrics ──────────────────────────────────────────────────
        double sharpeRatio,
        double sortinoRatio,
        double trackingErrorVsNifty,    // std(r_p − r_Nifty) × √252; NaN if benchmark unavailable

        // ── Headline for LLM ─────────────────────────────────────────────────────
        String headline

) {
    public record RiskContributor(
            String symbol,
            double weight,                      // market-value weight wᵢ
            double beta,                        // βᵢ vs Nifty 50
            double marginalContributionToRisk,  // MCRᵢ = (Σw)ᵢ / σ_p
            double componentContributionToRisk, // CCRᵢ = wᵢ × MCRᵢ; Σ = σ_p
            double percentContributionToRisk    // pᵢ = CCRᵢ / σ_p; Σ = 1
    ) {}
}
