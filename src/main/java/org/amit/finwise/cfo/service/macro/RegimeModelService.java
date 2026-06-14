package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Statistical regime detection via a 2-state Gaussian hidden Markov model (BPR-8).
 *
 * Where {@code MacroStateService} infers market state from deterministic FII-flow and
 * yield-curve thresholds, this fits a Markov-switching model (calm vs crisis) to index
 * daily returns and outputs a probabilistic regime signal — a smoothed crisis
 * probability plus expected state durations — that can be ensembled with the rules
 * rather than replacing them.
 *
 * The fit is a hand-rolled Baum-Welch EM (scaled forward-backward / Hamilton filter),
 * mirroring the pure-Java MLE style of {@code GarchService}. The crisis state is the
 * higher-variance state; emissions are Gaussian on daily returns.
 */
@Slf4j
@Service
public class RegimeModelService {

    private static final int MIN_OBS = 60;
    private static final int MAX_ITERS = 200;
    private static final double LOGLIK_TOL = 1e-7;
    private static final double VAR_FLOOR = 1e-12;
    private static final double PROB_FLOOR = 1e-8;
    private static final double TRADING_DAYS_YEAR = 252.0;

    public record RegimeResult(
            double crisisProbability,        // smoothed P(crisis) on the last observation
            double filteredCrisisProbability,// Hamilton-filtered P(crisis) on the last obs (real-time)
            double calmDailyVol,             // √σ² of the calm state
            double crisisDailyVol,           // √σ² of the crisis state
            double expectedCalmDurationDays, // 1/(1−a_calm,calm)
            double expectedCrisisDurationDays,
            double[] smoothedCrisisProb,     // full smoothed series, aligned to the returns
            int observations,
            int iterations,
            boolean converged
    ) {
        /** A compact line for the brief macro block. */
        public String render() {
            return String.format(
                    "Regime (2-state HMM): crisis prob %.2f (filtered %.2f) | calm vol %.1f%% vs "
                            + "crisis vol %.1f%% annualized | expected crisis spell ~%.0f trading days",
                    crisisProbability, filteredCrisisProbability,
                    calmDailyVol * Math.sqrt(TRADING_DAYS_YEAR) * 100,
                    crisisDailyVol * Math.sqrt(TRADING_DAYS_YEAR) * 100,
                    expectedCrisisDurationDays);
        }
    }

    /**
     * Fit the 2-state HMM to a daily return series (chronological). Empty when there are
     * too few observations or the series is degenerate (zero variance).
     */
    public Optional<RegimeResult> fit(double[] returns) {
        if (returns == null || returns.length < MIN_OBS) return Optional.empty();
        int T = returns.length;
        double sampleMean = StatUtils.mean(returns);
        double sampleVar = StatUtils.variance(returns);
        if (sampleVar < VAR_FLOOR) return Optional.empty();

        // ── Initialization: calm = low-vol (½ var), crisis = high-vol (2× var) ──
        double[] mu = {sampleMean, sampleMean};
        double[] var = {0.5 * sampleVar, 2.0 * sampleVar};
        // Persistent transitions; rows [from][to]. State 0 = calm, 1 = crisis.
        double[][] a = {{0.97, 0.03}, {0.10, 0.90}};
        double[] pi = {0.9, 0.1};

        double[][] gamma = new double[T][2];
        double[][][] xi = new double[T - 1][2][2];
        double prevLogLik = Double.NEGATIVE_INFINITY;
        int iter = 0;
        boolean converged = false;

        double[] cScale = new double[T];
        for (iter = 0; iter < MAX_ITERS; iter++) {
            // Emission densities b[t][k]
            double[][] b = new double[T][2];
            for (int t = 0; t < T; t++) {
                for (int k = 0; k < 2; k++) {
                    b[t][k] = Math.max(gaussian(returns[t], mu[k], var[k]), PROB_FLOOR);
                }
            }

            // ── Scaled forward (Hamilton filter) ──
            double[][] alpha = new double[T][2];
            double c0 = pi[0] * b[0][0] + pi[1] * b[0][1];
            cScale[0] = c0;
            alpha[0][0] = pi[0] * b[0][0] / c0;
            alpha[0][1] = pi[1] * b[0][1] / c0;
            for (int t = 1; t < T; t++) {
                double u0 = (alpha[t - 1][0] * a[0][0] + alpha[t - 1][1] * a[1][0]) * b[t][0];
                double u1 = (alpha[t - 1][0] * a[0][1] + alpha[t - 1][1] * a[1][1]) * b[t][1];
                double c = u0 + u1;
                cScale[t] = c;
                alpha[t][0] = u0 / c;
                alpha[t][1] = u1 / c;
            }

            // ── Scaled backward ──
            double[][] beta = new double[T][2];
            beta[T - 1][0] = 1.0;
            beta[T - 1][1] = 1.0;
            for (int t = T - 2; t >= 0; t--) {
                for (int i = 0; i < 2; i++) {
                    double s = 0;
                    for (int j = 0; j < 2; j++) s += a[i][j] * b[t + 1][j] * beta[t + 1][j];
                    beta[t][i] = s / cScale[t + 1];
                }
            }

            // ── Posteriors γ and ξ ──
            for (int t = 0; t < T; t++) {
                double g0 = alpha[t][0] * beta[t][0];
                double g1 = alpha[t][1] * beta[t][1];
                double gs = g0 + g1;
                gamma[t][0] = g0 / gs;
                gamma[t][1] = g1 / gs;
            }
            for (int t = 0; t < T - 1; t++) {
                double norm = 0;
                double[][] x = xi[t];
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        x[i][j] = alpha[t][i] * a[i][j] * b[t + 1][j] * beta[t + 1][j];
                        norm += x[i][j];
                    }
                }
                for (int i = 0; i < 2; i++)
                    for (int j = 0; j < 2; j++) x[i][j] /= norm;
            }

            // ── M-step ──
            pi[0] = gamma[0][0];
            pi[1] = gamma[0][1];
            for (int i = 0; i < 2; i++) {
                double denom = 0;
                for (int t = 0; t < T - 1; t++) denom += gamma[t][i];
                for (int j = 0; j < 2; j++) {
                    double num = 0;
                    for (int t = 0; t < T - 1; t++) num += xi[t][i][j];
                    a[i][j] = denom > 0 ? num / denom : a[i][j];
                }
            }
            for (int k = 0; k < 2; k++) {
                double w = 0, wMean = 0;
                for (int t = 0; t < T; t++) { w += gamma[t][k]; wMean += gamma[t][k] * returns[t]; }
                if (w <= 0) continue;
                mu[k] = wMean / w;
                double wVar = 0;
                for (int t = 0; t < T; t++) {
                    double d = returns[t] - mu[k];
                    wVar += gamma[t][k] * d * d;
                }
                var[k] = Math.max(wVar / w, VAR_FLOOR);
            }

            // Log-likelihood from the scaling factors.
            double logLik = 0;
            for (int t = 0; t < T; t++) logLik += Math.log(cScale[t]);
            if (Math.abs(logLik - prevLogLik) < LOGLIK_TOL) { converged = true; iter++; break; }
            prevLogLik = logLik;
        }

        // Crisis = higher-variance state.
        int crisis = var[1] >= var[0] ? 1 : 0;
        int calm = 1 - crisis;

        double[] smoothed = new double[T];
        for (int t = 0; t < T; t++) smoothed[t] = gamma[t][crisis];

        // Re-run the forward filter once to read the real-time (filtered) last-step prob.
        double filteredCrisis = filteredLast(returns, mu, var, a, pi, crisis);

        double expCalm = a[calm][calm] < 1.0 ? 1.0 / (1.0 - a[calm][calm]) : Double.POSITIVE_INFINITY;
        double expCrisis = a[crisis][crisis] < 1.0
                ? 1.0 / (1.0 - a[crisis][crisis]) : Double.POSITIVE_INFINITY;

        log.info("[Regime] HMM fit: crisis prob (smoothed last)={}, calm vol={}, crisis vol={}, iters={}",
                String.format("%.2f", smoothed[T - 1]),
                String.format("%.4f", Math.sqrt(var[calm])),
                String.format("%.4f", Math.sqrt(var[crisis])), iter);

        return Optional.of(new RegimeResult(
                smoothed[T - 1], filteredCrisis,
                Math.sqrt(var[calm]), Math.sqrt(var[crisis]),
                expCalm, expCrisis, smoothed, T, iter, converged));
    }

    /** Final-step Hamilton-filtered crisis probability (uses data up to T only). */
    private static double filteredLast(double[] returns, double[] mu, double[] var,
                                       double[][] a, double[] pi, int crisis) {
        int T = returns.length;
        double[] f = new double[2];
        double b00 = Math.max(gaussian(returns[0], mu[0], var[0]), PROB_FLOOR);
        double b01 = Math.max(gaussian(returns[0], mu[1], var[1]), PROB_FLOOR);
        double c = pi[0] * b00 + pi[1] * b01;
        f[0] = pi[0] * b00 / c;
        f[1] = pi[1] * b01 / c;
        for (int t = 1; t < T; t++) {
            double bt0 = Math.max(gaussian(returns[t], mu[0], var[0]), PROB_FLOOR);
            double bt1 = Math.max(gaussian(returns[t], mu[1], var[1]), PROB_FLOOR);
            double u0 = (f[0] * a[0][0] + f[1] * a[1][0]) * bt0;
            double u1 = (f[0] * a[0][1] + f[1] * a[1][1]) * bt1;
            double s = u0 + u1;
            f[0] = u0 / s;
            f[1] = u1 / s;
        }
        return f[crisis];
    }

    private static double gaussian(double x, double mu, double var) {
        double d = x - mu;
        return Math.exp(-0.5 * d * d / var) / Math.sqrt(2.0 * Math.PI * var);
    }
}
