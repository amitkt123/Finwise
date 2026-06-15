package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Index-based multi-factor risk decomposition (Phase 8).
 *
 * Per holding, an OLS regression r_i = α + β_mkt·MKT + β_size·SIZE + β_sec·SECTOR_s(i) + ε
 * with at most three regressors (the holding's OWN sector spread only, to avoid
 * multicollinearity). Portfolio exposures aggregate as B_k = Σ w_i β_ik; the factor
 * covariance goes through Ledoit-Wolf shrinkage.
 *
 * Sibling record to RiskDecomposition — the two are reported side by side, never merged.
 * systematic + idiosyncratic variance is reported AGAINST the direct wᵀΣw figure
 * (directVolAnnualized); the gap is estimation noise and is never forced to reconcile.
 *
 * t-stats use Newey-West HAC (heteroskedasticity- and autocorrelation-consistent)
 * standard errors with an automatic Bartlett bandwidth L = ⌊4·(T/100)^(2/9)⌋ —
 * treat |t| ≥ 2 as the display threshold for "significant", nothing more.
 */
public record FactorRiskReport(
        List<String> includedSymbols,
        List<String> excludedSymbols,
        double excludedWeightPct,            // raw weight share dropped, pre-renormalization
        List<String> dataQualityNotes,
        boolean isLowConfidence,
        LocalDate seriesFrom,
        LocalDate seriesTo,

        List<String> factorNames,            // canonical order: MKT, SIZE, sector tickers
        Map<String, Double> portfolioFactorBetas,   // factor → Σ wᵢ βᵢ

        double systematicVolAnnualized,      // √(BᵀΣ_F B · 252)
        double idiosyncraticVolAnnualized,   // √(Σ wᵢ²σ²_ε,ᵢ · 252)
        double totalVolAnnualized,           // model total = √(sys + idio variance)
        double systematicSharePct,           // systematic variance / model total, 0–100
        Map<String, Double> factorVarianceSharePct, // factor → % of systematic variance

        double directVolAnnualized,          // wᵀΣw on the same window; NaN if unavailable

        List<HoldingFactorExposure> holdings,
        String headline
) {

    /**
     * Per-holding regression result. betaSize/betaSector are null when the factor
     * was not in this holding's regression (missing SIZE series / unmapped sector).
     */
    public record HoldingFactorExposure(
            String symbol,
            double weight,                   // normalized to included holdings
            String sector,                   // gazetteer sector; may be null
            String sectorFactor,             // sector index ticker used; null if none
            double betaMkt,
            Double betaSize,
            Double betaSector,
            Map<String, Double> tStats,      // factor → t-stat (Newey-West HAC)
            double alphaAnnualized,          // daily intercept × 252
            double rSquared,
            double idioVolAnnualized,        // σ_ε·√252 from residual variance
            int observations,
            int hacLag                       // Bartlett bandwidth L used for the HAC t-stats
    ) {
        public boolean isSignificant(String factor) {
            Double t = tStats.get(factor);
            return t != null && Math.abs(t) >= 2.0;
        }
    }
}
