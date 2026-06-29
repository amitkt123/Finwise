package org.amit.finwise.goal.service;

import org.amit.finwise.goal.config.GoalMcProperties;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Monte-Carlo goal engine's pure simulation core.
 *
 * The engine takes a {@link GoalMcProperties} for path count and a fixed seed, so it can be
 * exercised without Spring or a {@link org.amit.finwise.cfo.service.analytics.PortfolioRiskService}
 * (passed null here — the GBM engine never touches it).
 */
class MonteCarloGoalServiceTest {

    private GoalMcProperties props(int paths, long seed) {
        GoalMcProperties p = new GoalMcProperties();
        p.setPaths(paths);
        p.setSeed(seed);
        return p;
    }

    private MonteCarloGoalService service(GoalMcProperties p) {
        return new MonteCarloGoalService(null, p, null);
    }

    @Test
    void fixedSeedIsReproducible() {
        GoalMcProperties p = props(4000, 20260612L);
        MonteCarloGoalService s = service(p);

        GoalSimulationResult a = s.simulateGbm(0, 10_000, 0.12, 0.18, 120, 2_000_000,
                p.getSeed(), false, new ArrayList<>());
        GoalSimulationResult b = s.simulateGbm(0, 10_000, 0.12, 0.18, 120, 2_000_000,
                p.getSeed(), false, new ArrayList<>());

        assertEquals(a.probabilityOfSuccess(), b.probabilityOfSuccess(), 0.0, "pSuccess identical");
        assertEquals(a.corpusP10(), b.corpusP10(), 0.0);
        assertEquals(a.corpusP50(), b.corpusP50(), 0.0);
        assertEquals(a.corpusP90(), b.corpusP90(), 0.0);
        assertEquals(a.requiredSip50(), b.requiredSip50(), 0.0);
        assertEquals(a.requiredSip90(), b.requiredSip90(), 0.0);
    }

    @Test
    void zeroVolDegeneratesToClosedFormAnnuity() {
        GoalMcProperties p = props(1000, 1L);
        MonteCarloGoalService s = service(p);

        double mu = 0.12, sip = 10_000, corpus0 = 50_000;
        int months = 120;

        // Closed-form future value of the recursion with σ=0:
        //   g = exp(μ/12); V_M = V0·g^M + S·(g^M − 1)/(g − 1)
        double g = Math.exp(mu / 12.0);
        double gM = Math.pow(g, months);
        double expected = corpus0 * gM + sip * (gM - 1.0) / (g - 1.0);

        GoalSimulationResult r = s.simulateGbm(corpus0, sip, mu, 0.0, months, expected * 0.5,
                p.getSeed(), false, new ArrayList<>());

        // Every path is identical, so all percentiles collapse to the closed form.
        assertEquals(expected, r.corpusP50(), 1e-6, "median equals closed-form annuity");
        assertEquals(r.corpusP10(), r.corpusP90(), 1e-9, "no dispersion when σ=0");
        assertEquals(expected, r.corpusP10(), 1e-6);
        assertEquals(1.0, r.probabilityOfSuccess(), 0.0, "corpus clears a target it exceeds");
    }

    @Test
    void requiredSipIsMonotoneInConfidence() {
        GoalMcProperties p = props(3000, 7L);
        MonteCarloGoalService s = service(p);

        GoalSimulationResult r = s.simulateGbm(0, 5_000, 0.10, 0.20, 180, 5_000_000,
                p.getSeed(), false, new ArrayList<>());

        assertTrue(r.requiredSip50() <= r.requiredSip75(),
                "50% confidence needs no more than 75%");
        assertTrue(r.requiredSip75() <= r.requiredSip90(),
                "75% confidence needs no more than 90%");
        assertTrue(r.requiredSip90() > 0, "a non-trivial target needs a positive SIP");
    }

    @Test
    void bootstrapResamplesHistoricalMonthlyReturns() {
        GoalMcProperties p = props(2000, 99L);
        MonteCarloGoalService s = service(p);

        // 36 months of mildly positive returns.
        double[] monthly = new double[36];
        for (int i = 0; i < monthly.length; i++) monthly[i] = 0.008 + (i % 5 - 2) * 0.01;

        GoalSimulationResult r = s.simulateBootstrap(100_000, 8_000, monthly, 120, 3_000_000,
                p.getSeed(), new ArrayList<>());

        assertEquals("BOOTSTRAP", r.mode());
        assertEquals(2000, r.paths());
        assertTrue(r.corpusP90() >= r.corpusP10(), "percentiles ordered");
        assertTrue(r.probabilityOfSuccess() >= 0.0 && r.probabilityOfSuccess() <= 1.0);
    }
}
