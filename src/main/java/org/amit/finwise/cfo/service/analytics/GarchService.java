package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.VolForecast;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.InitialGuess;
import org.apache.commons.math3.optim.MaxEval;
import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.apache.commons.math3.optim.nonlinear.scalar.ObjectiveFunction;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.NelderMeadSimplex;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.SimplexOptimizer;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Maximum-likelihood GARCH(1,1) volatility estimator (Phase 9a).
 *
 * Variance recursion on de-meaned returns ε:
 *   σ²_t = ω + α·ε²_{t-1} + β·σ²_{t-1},   σ²_1 seeded with the sample variance.
 *
 * Gaussian log-likelihood
 *   ℓ = −½ Σ [ ln(2π) + ln σ²_t + ε²_t/σ²_t ]
 * is maximized with Nelder-Mead over an unconstrained reparameterization that keeps
 * ω>0 and 0<α, 0<β, α+β<1 by construction:
 *   ω = exp(θ₁),  p = α+β = σ(θ₂)·0.9999,  α = p·σ(θ₃),  β = p − α.
 *
 * The fit is rejected (→ flat EWMA fallback, always with a note) when there is too
 * little data (T &lt; 250), the optimizer fails to converge, the process is effectively
 * integrated (α+β ≥ 0.999), or the implied unconditional vol is &gt; 3× the sample vol.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GarchService {

    private final RiskProperties riskProperties;

    /** Below this many observations a GARCH fit is unreliable; use EWMA. */
    static final int MIN_GARCH_OBSERVATIONS = 250;
    /** Reject near-integrated fits — variance forecasts don't mean-revert. */
    static final double MAX_PERSISTENCE = 0.999;
    /** Reject fits whose long-run vol is implausibly far above the sample vol. */
    static final double MAX_VOL_RATIO = 3.0;
    static final int FORECAST_HORIZON = 10;

    private static final double LN_2PI = Math.log(2.0 * Math.PI);
    private static final double SQRT_252 = Math.sqrt(252.0);
    private static final double VARIANCE_FLOOR = 1e-12;

    /** Fits using the configured single-equity EWMA lambda as the fallback decay. */
    public VolForecast fit(double[] returns) {
        return fit(returns, riskProperties.getEwmaLambdaEquity());
    }

    /**
     * @param returns       daily simple returns (chronological)
     * @param fallbackLambda EWMA decay used when GARCH is rejected
     */
    public VolForecast fit(double[] returns, double fallbackLambda) {
        if (returns == null || returns.length < 2) {
            throw new IllegalArgumentException("GARCH fit requires at least 2 returns");
        }
        int T = returns.length;
        double sampleVar = StatUtils.variance(returns);
        double sampleVol = Math.sqrt(sampleVar);

        // De-mean: GARCH models the conditional variance of residuals, not raw returns.
        double mean = StatUtils.mean(returns);
        double[] eps2 = new double[T];
        for (int t = 0; t < T; t++) {
            double e = returns[t] - mean;
            eps2[t] = e * e;
        }

        if (T < MIN_GARCH_OBSERVATIONS) {
            return ewmaFallback(eps2, sampleVar, fallbackLambda,
                    String.format("GARCH_FALLBACK: T=%d < %d observations; flat EWMA(λ=%.2f) used",
                            T, MIN_GARCH_OBSERVATIONS, fallbackLambda));
        }

        try {
            double[] theta = optimize(eps2, sampleVar);
            double[] params = transform(theta);             // {omega, alpha, beta}
            double omega = params[0], alpha = params[1], beta = params[2];
            double persistence = alpha + beta;

            if (persistence >= MAX_PERSISTENCE) {
                return ewmaFallback(eps2, sampleVar, fallbackLambda,
                        String.format("GARCH_FALLBACK: α+β=%.4f ≥ %.3f (near-integrated); EWMA used",
                                persistence, MAX_PERSISTENCE));
            }
            double sigma2LR = omega / (1.0 - persistence);
            if (Math.sqrt(sigma2LR) > MAX_VOL_RATIO * sampleVol) {
                return ewmaFallback(eps2, sampleVar, fallbackLambda,
                        String.format("GARCH_FALLBACK: implied vol %.4f > %.0f× sample vol %.4f; EWMA used",
                                Math.sqrt(sigma2LR), MAX_VOL_RATIO, sampleVol));
            }

            // Conditional variance path with the fitted parameters → σ²_T and ε²_T.
            double[] sigma2 = variancePath(eps2, sampleVar, omega, alpha, beta);
            double sigma2T = sigma2[T - 1];
            double sigma2Next = omega + alpha * eps2[T - 1] + beta * sigma2T;

            // Multi-step forecast: σ²_{t+h} = σ²_LR + p^{h-1}(σ²_{t+1} − σ²_LR).
            double varSum = 0.0;
            for (int h = 1; h <= FORECAST_HORIZON; h++) {
                varSum += sigma2LR + Math.pow(persistence, h - 1) * (sigma2Next - sigma2LR);
            }

            double logLik = logLikelihood(eps2, sigma2);
            List<String> notes = new ArrayList<>();
            log.info("[GARCH] fit ok: ω={}, α={}, β={}, persistence={}, LL={}",
                    fmt(omega), fmt(alpha), fmt(beta), fmt(persistence), fmt(logLik));

            return new VolForecast(
                    VolForecast.Method.GARCH, T,
                    omega, alpha, beta, persistence,
                    Math.sqrt(sigma2Next), Math.sqrt(sigma2LR),
                    Math.sqrt(varSum), Math.sqrt(sigma2Next) * SQRT_252,
                    logLik, notes);

        } catch (Exception e) {
            log.warn("[GARCH] optimization failed ({}); falling back to EWMA", e.toString());
            return ewmaFallback(eps2, sampleVar, fallbackLambda,
                    "GARCH_FALLBACK: optimizer did not converge; flat EWMA used");
        }
    }

    // ── Optimization ──────────────────────────────────────────────────────────

    private double[] optimize(double[] eps2, double sampleVar) {
        MultivariateFunction negLogLik = theta -> {
            double[] p = transform(theta);
            double[] sigma2 = variancePath(eps2, sampleVar, p[0], p[1], p[2]);
            return -logLikelihood(eps2, sigma2);
        };

        SimplexOptimizer optimizer = new SimplexOptimizer(1e-9, 1e-12);
        PointValuePair result = optimizer.optimize(
                new MaxEval(20_000),
                new ObjectiveFunction(negLogLik),
                GoalType.MINIMIZE,
                new InitialGuess(initialTheta(sampleVar)),
                new NelderMeadSimplex(3));
        return result.getPoint();
    }

    /** Inverts the reparameterization at α=0.05, β=0.90 (p=0.95). */
    private static double[] initialTheta(double sampleVar) {
        double p0 = 0.95, alpha0 = 0.05;
        double omega0 = Math.max(sampleVar * (1.0 - p0), VARIANCE_FLOOR);
        return new double[]{
                Math.log(omega0),
                logit(p0 / 0.9999),
                logit(alpha0 / p0)
        };
    }

    /** θ → {ω, α, β}. */
    static double[] transform(double[] theta) {
        double omega = Math.exp(theta[0]);
        double p = sigmoid(theta[1]) * 0.9999;
        double alpha = p * sigmoid(theta[2]);
        double beta = p - alpha;
        return new double[]{omega, alpha, beta};
    }

    private static double[] variancePath(double[] eps2, double seed,
                                         double omega, double alpha, double beta) {
        int T = eps2.length;
        double[] sigma2 = new double[T];
        sigma2[0] = Math.max(seed, VARIANCE_FLOOR);
        for (int t = 1; t < T; t++) {
            sigma2[t] = Math.max(omega + alpha * eps2[t - 1] + beta * sigma2[t - 1], VARIANCE_FLOOR);
        }
        return sigma2;
    }

    private static double logLikelihood(double[] eps2, double[] sigma2) {
        double sum = 0.0;
        for (int t = 0; t < eps2.length; t++) {
            sum += LN_2PI + Math.log(sigma2[t]) + eps2[t] / sigma2[t];
        }
        return -0.5 * sum;
    }

    // ── EWMA fallback ───────────────────────────────────────────────────────────

    /**
     * RiskMetrics EWMA: σ²_{t+1} = λσ²_t + (1−λ)ε²_t, seeded on the first ~20 obs.
     * Variance is a random walk, so the h-step forecast is flat and the 10-day vol is
     * simply √10 × the one-step vol.
     */
    private VolForecast ewmaFallback(double[] eps2, double sampleVar, double lambda, String note) {
        int T = eps2.length;
        int seedLen = Math.min(CovarianceEngine.EWMA_SEED_OBSERVATIONS, T);
        // eps2 are squared residuals; seed the recursion with their mean over the seed window.
        double variance = seedLen > 1 ? mean(eps2, seedLen) : eps2[0];
        for (int i = seedLen; i < T; i++) {
            variance = lambda * variance + (1 - lambda) * eps2[i];
        }
        double sigma2Next = Math.max(variance, VARIANCE_FLOOR);
        double dailyVol = Math.sqrt(sigma2Next);

        List<String> notes = new ArrayList<>();
        notes.add(note);
        return new VolForecast(
                VolForecast.Method.EWMA, T,
                null, null, null, lambda,
                dailyVol, Double.NaN,
                Math.sqrt(FORECAST_HORIZON) * dailyVol, dailyVol * SQRT_252,
                null, notes);
    }

    private static double mean(double[] a, int len) {
        double s = 0;
        for (int i = 0; i < len; i++) s += a[i];
        return s / len;
    }

    // ── small helpers ───────────────────────────────────────────────────────────

    static double sigmoid(double x) {
        if (x >= 0) {
            double z = Math.exp(-x);
            return 1.0 / (1.0 + z);
        }
        double z = Math.exp(x);
        return z / (1.0 + z);
    }

    static double logit(double y) {
        return Math.log(y / (1.0 - y));
    }

    private static String fmt(double d) {
        return String.format("%.6g", d);
    }
}
