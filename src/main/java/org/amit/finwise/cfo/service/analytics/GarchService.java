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
        double[] eps = new double[T];
        double[] eps2 = new double[T];
        for (int t = 0; t < T; t++) {
            double e = returns[t] - mean;
            eps[t] = e;
            eps2[t] = e * e;
        }

        if (T < MIN_GARCH_OBSERVATIONS) {
            return ewmaFallback(eps2, sampleVar, fallbackLambda,
                    String.format("GARCH_FALLBACK: T=%d < %d observations; flat EWMA(λ=%.2f) used",
                            T, MIN_GARCH_OBSERVATIONS, fallbackLambda));
        }

        VolForecast symmetric = fitSymmetric(eps2, sampleVar, sampleVol, T, fallbackLambda);

        if (!riskProperties.isAsymmetricGarchEnabled()) {
            return symmetric;
        }

        // Fit GJR-GARCH and select the better model by information criterion. When one
        // of the two fell back to EWMA (rejected fit) we keep the GARCH-method result;
        // when both are GARCH-method we pick the lower IC (BPR-5).
        VolForecast asymmetric = fitGjr(eps, eps2, sampleVar, sampleVol, T, fallbackLambda);
        return selectByCriterion(symmetric, asymmetric, T);
    }

    /** Symmetric GARCH(1,1) fit — the original Phase-9a estimator. */
    private VolForecast fitSymmetric(double[] eps2, double sampleVar, double sampleVol,
                                     int T, double fallbackLambda) {
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
            double sigma2Next = omega + alpha * eps2[T - 1] + beta * sigma2[T - 1];

            // Multi-step forecast: σ²_{t+h} = σ²_LR + p^{h-1}(σ²_{t+1} − σ²_LR).
            double varSum = 0.0;
            for (int h = 1; h <= FORECAST_HORIZON; h++) {
                varSum += sigma2LR + Math.pow(persistence, h - 1) * (sigma2Next - sigma2LR);
            }

            double logLik = logLikelihood(eps2, sigma2);
            log.info("[GARCH] symmetric fit ok: ω={}, α={}, β={}, persistence={}, LL={}",
                    fmt(omega), fmt(alpha), fmt(beta), fmt(persistence), fmt(logLik));

            return new VolForecast(
                    VolForecast.Method.GARCH, T,
                    omega, alpha, beta, null, persistence,
                    Math.sqrt(sigma2Next), Math.sqrt(sigma2LR),
                    Math.sqrt(varSum), Math.sqrt(sigma2Next) * SQRT_252,
                    logLik, new ArrayList<>());

        } catch (Exception e) {
            log.warn("[GARCH] symmetric optimization failed ({}); falling back to EWMA", e.toString());
            return ewmaFallback(eps2, sampleVar, fallbackLambda,
                    "GARCH_FALLBACK: optimizer did not converge; flat EWMA used");
        }
    }

    /**
     * GJR-GARCH(1,1,1) fit (BPR-5): captures the leverage effect — negative shocks
     * raise next-period variance more than positive shocks of equal size.
     *
     *   σ²_t = ω + (α + γ·1[ε_{t-1}<0])·ε²_{t-1} + β·σ²_{t-1}
     *
     * Stationarity uses E[1[ε&lt;0]] = ½, so persistence is α + γ/2 + β &lt; 1. The 4-parameter
     * unconstrained reparameterization keeps ω&gt;0, α,γ,β ≥ 0, and persistence &lt; 1 by
     * construction (γ ≥ 0 is the economically-expected leverage sign).
     */
    private VolForecast fitGjr(double[] eps, double[] eps2, double sampleVar, double sampleVol,
                               int T, double fallbackLambda) {
        try {
            double[] theta = optimizeGjr(eps, eps2, sampleVar);
            double[] params = transformGjr(theta);          // {omega, alpha, gamma, beta}
            double omega = params[0], alpha = params[1], gamma = params[2], beta = params[3];
            double persistence = alpha + 0.5 * gamma + beta;

            if (persistence >= MAX_PERSISTENCE) {
                return ewmaFallback(eps2, sampleVar, fallbackLambda,
                        String.format("GJR_FALLBACK: α+γ/2+β=%.4f ≥ %.3f (near-integrated); EWMA used",
                                persistence, MAX_PERSISTENCE));
            }
            double sigma2LR = omega / (1.0 - persistence);
            if (Math.sqrt(sigma2LR) > MAX_VOL_RATIO * sampleVol) {
                return ewmaFallback(eps2, sampleVar, fallbackLambda,
                        String.format("GJR_FALLBACK: implied vol %.4f > %.0f× sample vol %.4f; EWMA used",
                                Math.sqrt(sigma2LR), MAX_VOL_RATIO, sampleVol));
            }

            double[] sigma2 = variancePathGjr(eps, eps2, sampleVar, omega, alpha, gamma, beta);
            double leverageT = eps[T - 1] < 0 ? 1.0 : 0.0;
            double sigma2Next = omega + (alpha + gamma * leverageT) * eps2[T - 1] + beta * sigma2[T - 1];

            // Forward iteration uses the expected indicator ½, hence persistence above.
            double varSum = 0.0;
            for (int h = 1; h <= FORECAST_HORIZON; h++) {
                varSum += sigma2LR + Math.pow(persistence, h - 1) * (sigma2Next - sigma2LR);
            }

            double logLik = logLikelihood(eps2, sigma2);
            List<String> notes = new ArrayList<>();
            notes.add(String.format("GJR leverage coefficient γ=%.4f (negative-shock vol boost)", gamma));
            log.info("[GARCH] GJR fit ok: ω={}, α={}, γ={}, β={}, persistence={}, LL={}",
                    fmt(omega), fmt(alpha), fmt(gamma), fmt(beta), fmt(persistence), fmt(logLik));

            return new VolForecast(
                    VolForecast.Method.GJR_GARCH, T,
                    omega, alpha, beta, gamma, persistence,
                    Math.sqrt(sigma2Next), Math.sqrt(sigma2LR),
                    Math.sqrt(varSum), Math.sqrt(sigma2Next) * SQRT_252,
                    logLik, notes);

        } catch (Exception e) {
            log.warn("[GARCH] GJR optimization failed ({}); falling back to EWMA", e.toString());
            return ewmaFallback(eps2, sampleVar, fallbackLambda,
                    "GJR_FALLBACK: optimizer did not converge; flat EWMA used");
        }
    }

    /**
     * Pick the better of the symmetric and asymmetric fits by information criterion.
     * AIC = 2k − 2ℓ, BIC = k·ln(T) − 2ℓ (lower is better). GARCH has k=3 free
     * parameters, GJR has k=4. A rejected fit (EWMA fallback, null logLik) loses to a
     * valid GARCH-method fit; if both fell back, the symmetric EWMA is returned.
     */
    VolForecast selectByCriterion(VolForecast symmetric, VolForecast asymmetric, int T) {
        boolean useBic = "BIC".equalsIgnoreCase(riskProperties.getGarchSelectionCriterion());
        Double icSym = infoCriterion(symmetric, 3, T, useBic);
        Double icAsym = infoCriterion(asymmetric, 4, T, useBic);

        if (icSym == null && icAsym == null) return symmetric; // both fell back
        if (icSym == null) return asymmetric;
        if (icAsym == null) return symmetric;

        VolForecast chosen = icAsym < icSym ? asymmetric : symmetric;
        log.info("[GARCH] model selection ({}): symmetric={}, asymmetric={} → {}",
                useBic ? "BIC" : "AIC", fmt(icSym), fmt(icAsym), chosen.method());
        return chosen;
    }

    /** Information criterion for a fitted model, or null when it is not a GARCH-method fit. */
    private static Double infoCriterion(VolForecast vf, int k, int T, boolean useBic) {
        if (vf == null || !vf.isGarch() || vf.logLikelihood() == null) return null;
        double penalty = useBic ? k * Math.log(T) : 2.0 * k;
        return penalty - 2.0 * vf.logLikelihood();
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

    // ── GJR-GARCH numerics (BPR-5) ──────────────────────────────────────────────

    private double[] optimizeGjr(double[] eps, double[] eps2, double sampleVar) {
        MultivariateFunction negLogLik = theta -> {
            double[] p = transformGjr(theta);
            double[] sigma2 = variancePathGjr(eps, eps2, sampleVar, p[0], p[1], p[2], p[3]);
            return -logLikelihood(eps2, sigma2);
        };
        SimplexOptimizer optimizer = new SimplexOptimizer(1e-9, 1e-12);
        PointValuePair result = optimizer.optimize(
                new MaxEval(40_000),
                new ObjectiveFunction(negLogLik),
                GoalType.MINIMIZE,
                new InitialGuess(initialThetaGjr(sampleVar)),
                new NelderMeadSimplex(4));
        return result.getPoint();
    }

    /** Inverts the GJR reparameterization at α=0.03, γ=0.06, β=0.90 (persistence 0.96). */
    private static double[] initialThetaGjr(double sampleVar) {
        double alpha0 = 0.03, gamma0 = 0.06, beta0 = 0.90;
        double p0 = alpha0 + 0.5 * gamma0 + beta0;          // 0.96
        double omega0 = Math.max(sampleVar * (1.0 - p0), VARIANCE_FLOOR);
        // Softmax logits for shares (α, γ/2, β) of p, baselined on the β component.
        double a = alpha0, g = 0.5 * gamma0, b = beta0;
        return new double[]{
                Math.log(omega0),
                logit(p0 / 0.9999),
                Math.log(a / b),
                Math.log(g / b)
        };
    }

    /** θ → {ω, α, γ, β}; shares of persistence p via softmax(θ₃, θ₄, 0). */
    static double[] transformGjr(double[] theta) {
        double omega = Math.exp(theta[0]);
        double p = sigmoid(theta[1]) * 0.9999;
        double e3 = Math.exp(theta[2]);
        double e4 = Math.exp(theta[3]);
        double denom = e3 + e4 + 1.0;                       // third logit baselined at 0
        double wAlpha = e3 / denom;
        double wHalfGamma = e4 / denom;
        double wBeta = 1.0 / denom;
        double alpha = p * wAlpha;
        double gamma = 2.0 * p * wHalfGamma;
        double beta = p * wBeta;
        return new double[]{omega, alpha, gamma, beta};
    }

    private static double[] variancePathGjr(double[] eps, double[] eps2, double seed,
                                            double omega, double alpha, double gamma, double beta) {
        int T = eps2.length;
        double[] sigma2 = new double[T];
        sigma2[0] = Math.max(seed, VARIANCE_FLOOR);
        for (int t = 1; t < T; t++) {
            double leverage = eps[t - 1] < 0 ? 1.0 : 0.0;
            sigma2[t] = Math.max(
                    omega + (alpha + gamma * leverage) * eps2[t - 1] + beta * sigma2[t - 1],
                    VARIANCE_FLOOR);
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
                null, null, null, null, lambda,
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
