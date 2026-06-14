package org.amit.finwise.cfo.model;

import java.util.List;

/**
 * Forward-looking volatility forecast (Phase 9a).
 *
 * Produced by {@code GarchService} from a single de-meaned return series. When a
 * GARCH(1,1) fit is reliable the conditional variance is mean-reverting toward the
 * long-run level {@code sigma2_LR = omega/(1−alpha−beta)}; otherwise the forecast
 * degrades to a flat EWMA (RiskMetrics) projection and {@code method = EWMA}.
 *
 * Volatilities are reported in daily units (per-day standard deviation of returns);
 * {@code annualizedVol} scales the one-step conditional vol by √252. {@code tenDayVol}
 * is the √(Σ conditional variances) over a 10-day horizon — NOT √10 × dailyVol unless
 * the series is a random walk (the EWMA case).
 */
public record VolForecast(
        Method method,
        int observations,

        Double omega,                 // GARCH only; null under EWMA fallback
        Double alpha,
        Double beta,
        Double leverageGamma,         // GJR-GARCH leverage coeff γ; null for symmetric GARCH / EWMA
        double persistence,           // GARCH: α+β (GJR: α+γ/2+β); λ for EWMA

        double conditionalDailyVol,   // √sigma2_{t+1} (one-step-ahead)
        double longRunDailyVol,       // √sigma2_LR; NaN under EWMA (no mean reversion)
        double tenDayVol,             // √Σ_{h=1..10} sigma2_{t+h}
        double annualizedVol,         // conditionalDailyVol × √252

        Double logLikelihood,         // GARCH only; null under EWMA fallback
        List<String> notes
) {
    public enum Method { GARCH, GJR_GARCH, EWMA }

    public boolean isGarch() {
        return method == Method.GARCH || method == Method.GJR_GARCH;
    }

    /** True when the asymmetric (leverage-effect) model was selected. */
    public boolean isAsymmetric() {
        return method == Method.GJR_GARCH;
    }
}
