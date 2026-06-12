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
}
