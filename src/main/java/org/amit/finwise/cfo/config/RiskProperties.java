package org.amit.finwise.cfo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Consolidated risk-engine configuration (prefix {@code cfo.risk}).
 *
 * EWMA decay differs by asset class: 0.94 is the RiskMetrics (1996) daily
 * calibration for single equities; indices and diversified funds are smoother,
 * so a slower decay (0.97) is appropriate.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cfo.risk")
public class RiskProperties {

    /** Annualized risk-free rate (10Y G-sec yield). */
    private double riskFreeRate = 0.071;

    /** Calendar-day lookback for return series used in risk estimation. */
    private int lookbackDays = 365;

    /** EWMA decay for single-stock volatility (cfo.risk.ewma-lambda-equity). */
    private double ewmaLambdaEquity = 0.94;

    /** EWMA decay for index/fund volatility (cfo.risk.ewma-lambda-index). */
    private double ewmaLambdaIndex = 0.97;

    /** Ledoit-Wolf shrinkage of the covariance matrix (Phase 2). */
    private boolean shrinkageEnabled = true;

    /**
     * Fit GJR-GARCH(1,1,1) alongside symmetric GARCH(1,1) and select the better fit
     * by information criterion (BPR-5). When false, only symmetric GARCH is fitted.
     */
    private boolean asymmetricGarchEnabled = true;

    /** Information criterion for symmetric-vs-asymmetric GARCH selection: AIC or BIC. */
    private String garchSelectionCriterion = "AIC";

    /**
     * Apply the Dimson (1979) lead/lag beta correction for stale-priced illiquid names
     * (BPR-9). When the lead/lag-summed beta materially exceeds the naive OLS beta (the
     * downward stale-price bias), the corrected beta is used and the report notes it.
     */
    private boolean dimsonCorrectionEnabled = false;

    /** Minimum upward gap (corrected − naive) before the Dimson beta replaces the naive one. */
    private double dimsonMinBetaGap = 0.15;

    /**
     * Long-run annual equity risk premium over the risk-free rate, used as the Bayesian prior
     * that trailing sample-mean drift is shrunk toward in multi-year Monte Carlo projections
     * (a 3-year daily-return sample mean has a standard error far larger than its own
     * magnitude, so it cannot be trusted unshrunk over a decade-long horizon).
     */
    private double equityRiskPremium = 0.05;

    /**
     * Pseudo-count (in equivalent trading days) for drift shrinkage: {@code muShrunk =
     * (T·muSample + K·muPrior) / (T + K)} — the same shrinkage shape used by
     * {@code ConfidenceCalibrationService}. Set high (default ≈10 trading years) because a
     * daily-return sample mean stays noisy for far longer than a typical lookback window.
     */
    private double driftShrinkageDays = 2500;
}
