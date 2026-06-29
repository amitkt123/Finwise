package org.amit.finwise.goal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.goal.config.GoalMcProperties;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Monte-Carlo goal engine.
 *
 * Simulates terminal wealth for a goal under monthly contributions, then reports the
 * probability of reaching the inflation-adjusted target and the SIP required for 50/75/90%
 * confidence. Two engines:
 *
 *   GBM       — geometric Brownian motion, V_{m+1} = V_m·exp((μ − σ²/2)·dt + σ·√dt·Z) + SIP,
 *               dt = 1/12. μ/σ come from {@link PortfolioRiskService#estimateDriftVol}, falling
 *               back to the goal's expectedAnnualReturn and a default asset-mix volatility.
 *   BOOTSTRAP — resamples historical monthly portfolio returns (≥ bootstrapMinMonths required),
 *               selected via {@code cfo.goal.mc-mode=bootstrap}.
 *
 * Determinism: every simulation is seeded from {@link GoalMcProperties#getSeed()}. The
 * required-SIP search re-runs under that one seed so the success probability is monotone in
 * the contribution, making the bisection well-posed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonteCarloGoalService {

    private final PortfolioRiskService portfolioRiskService;
    private final GoalMcProperties props;
    private final QuantitativeMacroState macroState;

    private static final double DT = 1.0 / 12.0;
    private static final double SQRT_DT = Math.sqrt(DT);
    private static final int BISECTION_ITERS = 30;
    private static final double SIP_TOLERANCE = 100.0;   // ₹ resolution of required-SIP search

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Simulate a goal at the given current monthly SIP. When {@code currentMonthlySip} is
     * null, the goal's required monthly amount is used as the assumed contribution.
     */
    public GoalSimulationResult simulate(FinancialGoal goal, Double currentMonthlySip) {
        long months = Math.max(0, ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate()));
        double sip = currentMonthlySip != null ? currentMonthlySip
                : goal.getRequiredMonthlyAmount() != null ? goal.getRequiredMonthlyAmount().doubleValue()
                : 0.0;
        double corpus0 = goal.getCurrentAmount() != null ? goal.getCurrentAmount().doubleValue() : 0.0;
        double target = inflationAdjustedTarget(goal, months);

        List<String> notes = new ArrayList<>();

        // ── Bootstrap engine (opt-in, history-permitting) ────────────────────────
        if ("bootstrap".equalsIgnoreCase(props.getMcMode())) {
            Optional<double[]> monthly = portfolioRiskService.monthlyPortfolioReturns(goal.getUserId());
            if (monthly.isPresent() && monthly.get().length >= props.getBootstrapMinMonths()) {
                return simulateBootstrap(corpus0, sip, monthly.get(), (int) months, target,
                        props.getSeed(), notes);
            }
            notes.add("BOOTSTRAP_UNAVAILABLE: fewer than " + props.getBootstrapMinMonths()
                    + " monthly observations; using GBM");
        }

        // ── GBM engine: μ/σ from portfolio history, else planning fallback ────────
        Optional<PortfolioRiskService.DriftVol> dv = portfolioRiskService.estimateDriftVol(goal.getUserId());
        double mu, sigma;
        boolean lowConfidence;
        if (dv.isPresent()) {
            mu = dv.get().annualDrift();
            sigma = dv.get().annualVolatility();
            lowConfidence = false;
        } else {
            mu = goal.getExpectedAnnualReturn() != null
                    ? goal.getExpectedAnnualReturn().doubleValue() / 100.0
                    : props.getDefaultDrift();
            sigma = props.getDefaultVol();
            lowConfidence = true;
            notes.add(String.format(
                    "MU_SIGMA_FALLBACK: insufficient portfolio history; μ=%.1f%%, σ=%.1f%% assumed",
                    mu * 100, sigma * 100));
        }

        // ── Regime-conditional σ/μ adjustment from QuantitativeMacroState ────────
        boolean regimeAdjusted = false;
        double effectiveSigma = sigma;
        if (macroState != null) {
            double p = macroState.getCrisisProbability();
            double sigmaCalm = macroState.getRegimeVolCalm();
            double sigmaCrisis = macroState.getRegimeVolCrisis();

            if (!Double.isNaN(sigmaCalm) && !Double.isNaN(sigmaCrisis) && p > 0.0) {
                effectiveSigma = (1 - p) * sigmaCalm + p * sigmaCrisis;
                regimeAdjusted = true;
                // Drift penalty: 4% annual drag at p=1.0
                mu = mu - p * 0.04;
                notes.add(String.format(
                        "REGIME_BLEND: p_crisis=%.2f, σ_calm=%.1f%%, σ_crisis=%.1f%% → σ_eff=%.1f%%",
                        p, sigmaCalm * 100, sigmaCrisis * 100, effectiveSigma * 100));
            }

            // Yield curve real-rate floor
            double yieldFloor = macroState.getYieldCurve10y();
            if (!Double.isNaN(yieldFloor) && goal.getInflationRate() != null && goal.getInflationRate().doubleValue() > 0) {
                mu = Math.max(mu, yieldFloor - goal.getInflationRate().doubleValue() / 100.0);
            }
        }
        sigma = effectiveSigma;

        return simulateGbm(corpus0, sip, mu, sigma, (int) months, target,
                props.getSeed(), lowConfidence, regimeAdjusted, notes);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Engines (package-visible for direct testing without Spring wiring)
    // ─────────────────────────────────────────────────────────────────────────

    /** GBM simulation and full result assembly. */
    GoalSimulationResult simulateGbm(double corpus0, double sip, double mu, double sigma,
                                     int months, double target, long seed,
                                     boolean lowConfidence, List<String> notes) {
        PathSimulator sim = (s, sd) -> gbmFinalCorpuses(corpus0, s, mu, sigma, months, sd);
        return assemble("GBM", months, mu, sigma, lowConfidence, false, sip, target, seed, sim, notes);
    }

    /** GBM simulation and full result assembly (with regime-adjustment flag). */
    GoalSimulationResult simulateGbm(double corpus0, double sip, double mu, double sigma,
                                     int months, double target, long seed,
                                     boolean lowConfidence, boolean regimeAdjusted, List<String> notes) {
        PathSimulator sim = (s, sd) -> gbmFinalCorpuses(corpus0, s, mu, sigma, months, sd);
        return assemble("GBM", months, mu, sigma, lowConfidence, regimeAdjusted, sip, target, seed, sim, notes);
    }

    /** Historical-bootstrap simulation and full result assembly. */
    GoalSimulationResult simulateBootstrap(double corpus0, double sip, double[] monthlyReturns,
                                           int months, double target, long seed, List<String> notes) {
        double mu = annualizedFromMonthly(monthlyReturns, true);
        double sigma = annualizedFromMonthly(monthlyReturns, false);
        PathSimulator sim = (s, sd) -> bootstrapFinalCorpuses(corpus0, s, monthlyReturns, months, sd);
        return assemble("BOOTSTRAP", months, mu, sigma, false, false, sip, target, seed, sim, notes);
    }

    @FunctionalInterface
    interface PathSimulator {
        /** Terminal corpus of every path for a given SIP under a given RNG seed. */
        double[] finalCorpuses(double sip, long seed);
    }

    private GoalSimulationResult assemble(String mode, int months, double mu, double sigma,
                                          boolean lowConfidence, boolean regimeAdjusted,
                                          double sip, double target,
                                          long seed, PathSimulator sim, List<String> notes) {
        double[] finals = sim.finalCorpuses(sip, seed);
        Arrays.sort(finals);

        double pSuccess = successFraction(finals, target);
        double requiredSip50 = requiredSip(0.50, target, seed, sim);
        double requiredSip75 = requiredSip(0.75, target, seed, sim);
        double requiredSip90 = requiredSip(0.90, target, seed, sim);

        String headline = String.format(
                "%.0f%% chance at ₹%,.0f/mo; ₹%,.0f/mo for 90%% confidence.",
                pSuccess * 100, sip, requiredSip90);

        return new GoalSimulationResult(
                mode, finals.length, months,
                mu, sigma, lowConfidence,
                sip, target,
                pSuccess,
                quantile(finals, 0.10), quantile(finals, 0.25), quantile(finals, 0.50),
                quantile(finals, 0.75), quantile(finals, 0.90),
                requiredSip50, requiredSip75, requiredSip90,
                headline, List.copyOf(notes),
                regimeAdjusted, sigma);
    }

    // ── Path generation ──────────────────────────────────────────────────────

    /** GBM terminal corpuses: V_{m+1} = V_m·exp((μ − σ²/2)·dt + σ·√dt·Z) + SIP. */
    double[] gbmFinalCorpuses(double corpus0, double sip, double mu, double sigma,
                              int months, long seed) {
        Random rng = new Random(seed);
        double drift = (mu - 0.5 * sigma * sigma) * DT;
        double diffusion = sigma * SQRT_DT;
        double[] finals = new double[props.getPaths()];
        for (int p = 0; p < finals.length; p++) {
            double v = corpus0;
            for (int m = 0; m < months; m++) {
                v = v * Math.exp(drift + diffusion * rng.nextGaussian()) + sip;
            }
            finals[p] = v;
        }
        return finals;
    }

    /** Bootstrap terminal corpuses: V_{m+1} = V_m·(1 + r) + SIP, r resampled with replacement. */
    double[] bootstrapFinalCorpuses(double corpus0, double sip, double[] monthlyReturns,
                                    int months, long seed) {
        Random rng = new Random(seed);
        double[] finals = new double[props.getPaths()];
        for (int p = 0; p < finals.length; p++) {
            double v = corpus0;
            for (int m = 0; m < months; m++) {
                double r = monthlyReturns[rng.nextInt(monthlyReturns.length)];
                v = v * (1.0 + r) + sip;
            }
            finals[p] = v;
        }
        return finals;
    }

    // ── Required-SIP search ──────────────────────────────────────────────────

    /**
     * Smallest SIP whose simulated success probability reaches {@code targetConfidence},
     * via bisection under one fixed seed (so probability is monotone in SIP). Returns 0 when
     * the existing corpus already clears the bar, or NaN when the bar is out of search range.
     */
    double requiredSip(double targetConfidence, double target, long seed, PathSimulator sim) {
        if (pSuccessAt(0.0, target, seed, sim) >= targetConfidence) return 0.0;

        // Bracket: double the upper bound until it satisfies the confidence (capped).
        double hi = 1000.0;
        int caps = 0;
        while (pSuccessAt(hi, target, seed, sim) < targetConfidence) {
            hi *= 2.0;
            if (++caps > 25) return Double.NaN;   // unreachable within ~3.3 crore/month
        }

        double lo = 0.0;
        for (int i = 0; i < BISECTION_ITERS && hi - lo > SIP_TOLERANCE; i++) {
            double mid = 0.5 * (lo + hi);
            if (pSuccessAt(mid, target, seed, sim) >= targetConfidence) hi = mid;
            else lo = mid;
        }
        // Round up to the confident side, to the nearest ₹100.
        return Math.ceil(hi / SIP_TOLERANCE) * SIP_TOLERANCE;
    }

    private double pSuccessAt(double sip, double target, long seed, PathSimulator sim) {
        return successFraction(sim.finalCorpuses(sip, seed), target);
    }

    // ── Small numeric helpers ────────────────────────────────────────────────

    private static double successFraction(double[] finals, double target) {
        int ok = 0;
        for (double v : finals) if (v >= target) ok++;
        return (double) ok / finals.length;
    }

    /**
     * Linear-interpolated empirical quantile (Hyndman-Fan R-7) on an ascending array.
     * Same convention used for historical VaR in the risk engine.
     */
    static double quantile(double[] sortedAscending, double p) {
        int n = sortedAscending.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return sortedAscending[0];
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        if (lo + 1 >= n) return sortedAscending[n - 1];
        return sortedAscending[lo] + (h - lo) * (sortedAscending[lo + 1] - sortedAscending[lo]);
    }

    /** Inflation-adjusted future-value target, mirroring GoalAnalyzerService.analyzeGoal. */
    private static double inflationAdjustedTarget(FinancialGoal goal, long months) {
        double target = goal.getTargetAmount().doubleValue();
        if (goal.getInflationRate() != null && months > 0) {
            double monthlyInflation = goal.getInflationRate().doubleValue() / 100.0 / 12.0;
            target *= Math.pow(1.0 + monthlyInflation, months);
        }
        return target;
    }

    /** Geometric-mean monthly return (×12 for drift) or monthly stdev (×√12 for vol), annualized. */
    private static double annualizedFromMonthly(double[] monthlyReturns, boolean drift) {
        if (drift) {
            double logSum = 0.0;
            for (double r : monthlyReturns) logSum += Math.log(1.0 + r);
            double geoMonthly = Math.exp(logSum / monthlyReturns.length) - 1.0;
            return Math.pow(1.0 + geoMonthly, 12.0) - 1.0;
        }
        double mean = Arrays.stream(monthlyReturns).average().orElse(0.0);
        double var = Arrays.stream(monthlyReturns).map(r -> (r - mean) * (r - mean)).sum()
                / Math.max(1, monthlyReturns.length - 1);
        return Math.sqrt(var) * Math.sqrt(12.0);
    }
}
