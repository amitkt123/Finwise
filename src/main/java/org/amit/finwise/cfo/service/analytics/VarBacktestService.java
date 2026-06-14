package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.VarBacktestReport;
import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis;
import org.apache.commons.math3.stat.descriptive.moment.Skewness;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Backtests the portfolio VaR the engine already reports (Phase A).
 *
 * Validation is rolling and out-of-sample: at each day t ≥ W the VaR is re-estimated from
 * the trailing window [t−W, t−1] and a breach is flagged when {@code return[t] < −VaR_t}.
 * The breach series feeds a Kupiec proportion-of-failures test (unconditional coverage)
 * and a Christoffersen Markov test (breach independence). χ² p-values come from
 * Commons-Math, already a dependency.
 *
 * The service is package-private to {@code analytics} so it can reuse
 * {@link PortfolioRiskService#portfolioReturnSeries(String)} (the same value-weighted
 * series the live VaR is computed from) and the engine's Cornish-Fisher / empirical-quantile
 * helpers — keeping the backtested VaR definition byte-identical to the rendered one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VarBacktestService {

    private final PortfolioRiskService portfolioRiskService;

    /** Default rolling estimation window (≈1 trading year). */
    static final int DEFAULT_WINDOW = 250;
    /** VaR confidence levels validated on every run. */
    static final double[] LEVELS = {0.05, 0.01};

    private static final ChiSquaredDistribution CHI2_1 = new ChiSquaredDistribution(1);
    private static final NormalDistribution STD_NORMAL = new NormalDistribution();

    /** VaR variant whose threshold is re-estimated in each rolling window. */
    public enum VarMethod {
        PARAMETRIC("Parametric"),
        CORNISH_FISHER("Cornish-Fisher"),
        HISTORICAL("Historical");

        final String label;
        VarMethod(String label) { this.label = label; }
    }

    /**
     * Backtests the value-weighted portfolio VaR at both 95% and 99% using the
     * Cornish-Fisher variant — the fat-tail-adjusted figure the brief leads with.
     * Empty when the portfolio lacks enough aligned history for at least one
     * out-of-sample day beyond the rolling window.
     */
    public List<VarBacktestReport> backtest(String userId) {
        double[] returns = portfolioRiskService.portfolioReturnSeries(userId);
        if (returns == null || returns.length <= DEFAULT_WINDOW + 1) {
            return List.of();
        }
        List<VarBacktestReport> reports = new ArrayList<>();
        for (double p : LEVELS) {
            VarBacktestReport r = backtest(returns, DEFAULT_WINDOW, p, VarMethod.CORNISH_FISHER);
            if (r != null) reports.add(r);
        }
        return reports;
    }

    /**
     * Rolling out-of-sample backtest of one VaR method at one confidence level.
     * Returns null when fewer than two out-of-sample days exist (no transition counts).
     */
    static VarBacktestReport backtest(double[] returns, int window, double p, VarMethod method) {
        int n = returns.length;
        int testDays = n - window;
        if (testDays < 2) return null;

        // z is the one-sided Gaussian quantile magnitude (1.645 @95%, 2.326 @99%).
        double z = -STD_NORMAL.inverseCumulativeProbability(p);

        boolean[] breach = new boolean[testDays];
        int x = 0;
        for (int t = window; t < n; t++) {
            double[] win = Arrays.copyOfRange(returns, t - window, t);
            double varFrac = varThreshold(win, p, z, method);   // positive loss fraction
            boolean b = returns[t] < -varFrac;
            breach[t - window] = b;
            if (b) x++;
        }

        int T = testDays;
        double piHat = (double) x / T;

        // ── Kupiec POF (unconditional coverage), LR_uc ~ χ²(1) ──────────────────
        double kupiecLR = -2.0 * (binomialLogLik(x, T, p) - binomialLogLik(x, T, piHat));
        kupiecLR = Math.max(0.0, kupiecLR);
        double kupiecP = 1.0 - CHI2_1.cumulativeProbability(kupiecLR);
        boolean kupiecReject = kupiecP < 0.05;

        // ── Christoffersen independence, LR_ind ~ χ²(1) ─────────────────────────
        int n00 = 0, n01 = 0, n10 = 0, n11 = 0;
        for (int t = 1; t < T; t++) {
            boolean prev = breach[t - 1], cur = breach[t];
            if (!prev && !cur) n00++;
            else if (!prev) n01++;
            else if (!cur) n10++;
            else n11++;
        }
        double pi   = (double) (n01 + n11) / (n00 + n01 + n10 + n11);
        double pi01 = (n00 + n01) > 0 ? (double) n01 / (n00 + n01) : 0.0;
        double pi11 = (n10 + n11) > 0 ? (double) n11 / (n10 + n11) : 0.0;
        double l1 = logTerm(n00, 1 - pi01) + logTerm(n01, pi01)
                  + logTerm(n10, 1 - pi11) + logTerm(n11, pi11);
        double l0 = logTerm(n00 + n10, 1 - pi) + logTerm(n01 + n11, pi);
        double indLR = Math.max(0.0, -2.0 * (l0 - l1));
        double indP = 1.0 - CHI2_1.cumulativeProbability(indLR);
        boolean clustering = indP < 0.05;

        String verdict = verdict(p, piHat, kupiecReject, clustering);

        return new VarBacktestReport(
                method.label, window, p, T, x, p, piHat,
                kupiecLR, kupiecP, kupiecReject,
                indLR, indP, clustering,
                verdict);
    }

    /**
     * VaR threshold (a positive loss fraction) for the trailing window under the chosen
     * method. A breach is {@code realizedReturn < −threshold}. Reuses the engine's
     * Cornish-Fisher and empirical-quantile routines so the definition matches the live VaR.
     */
    private static double varThreshold(double[] win, double p, double z, VarMethod method) {
        switch (method) {
            case HISTORICAL -> {
                double[] sorted = win.clone();
                Arrays.sort(sorted);
                return -PortfolioRiskService.interpolatedQuantile(sorted, p);
            }
            case CORNISH_FISHER -> {
                double sigma = Math.sqrt(StatUtils.variance(win));
                double skew = new Skewness().evaluate(win);
                double exKurt = new Kurtosis().evaluate(win);
                double zCf = PortfolioRiskService.cornishFisherValid(skew, exKurt)
                        ? PortfolioRiskService.cornishFisherZ(-z, skew, exKurt)
                        : -z;
                return -zCf * sigma;   // zCf < 0 in the loss tail → positive threshold
            }
            default -> {
                double sigma = Math.sqrt(StatUtils.variance(win));
                return z * sigma;
            }
        }
    }

    /** Binomial log-likelihood x·ln(q) + (T−x)·ln(1−q), with the 0·ln0 = 0 convention. */
    private static double binomialLogLik(int x, int T, double q) {
        return logTerm(x, q) + logTerm(T - x, 1 - q);
    }

    /** count·ln(prob), returning 0 when count is 0 (so an undefined ln(0) never propagates). */
    private static double logTerm(int count, double prob) {
        return count == 0 ? 0.0 : count * Math.log(prob);
    }

    private static String verdict(double p, double actualRate,
                                  boolean kupiecReject, boolean clustering) {
        String base;
        if (kupiecReject) {
            base = actualRate > p ? "VaR understates tails" : "VaR overstates tails (too conservative)";
        } else {
            base = "VaR well-calibrated";
        }
        if (clustering) {
            base += "; breaches cluster (volatility timing missed)";
        }
        return base;
    }
}
