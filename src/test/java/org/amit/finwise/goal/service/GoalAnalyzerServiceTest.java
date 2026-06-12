package org.amit.finwise.goal.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known-answer tests for the goal-planning PMT formula.
 *
 * Reference case: ₹1 Cr in 120 months at 12% p.a. (1% monthly):
 *   PMT = 1e7 × 0.01 / (1.01^120 − 1) ≈ ₹43,471
 * versus the old linear FV/N = ₹83,333 — nearly double.
 */
class GoalAnalyzerServiceTest {

    @Test
    void pmt_matchesHandComputedAnnuityValue() {
        BigDecimal pmt = GoalAnalyzerService.requiredMonthlyContribution(
                BigDecimal.valueOf(10_000_000), BigDecimal.ZERO,
                BigDecimal.valueOf(12), 120);

        assertEquals(43_471, pmt.doubleValue(), 1.0,
                "PMT for ₹1 Cr / 120 months / 12% p.a. should be ≈ ₹43,471");
    }

    @Test
    void fallsBackToLinearWhenNoReturnAssumption() {
        BigDecimal monthly = GoalAnalyzerService.requiredMonthlyContribution(
                BigDecimal.valueOf(1_200_000), BigDecimal.ZERO, null, 12);

        assertEquals(0, BigDecimal.valueOf(100_000).compareTo(monthly),
                "without a return assumption, FV/N is the only defensible answer");
    }

    @Test
    void existingCorpusCompoundsAndReducesRequiredSaving() {
        // ₹50L corpus at 12% for 10y grows to ~₹1.65 Cr — already past a ₹1 Cr target
        BigDecimal pmt = GoalAnalyzerService.requiredMonthlyContribution(
                BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(5_000_000),
                BigDecimal.valueOf(12), 120);

        assertEquals(0, BigDecimal.ZERO.compareTo(pmt),
                "corpus compounding past the target needs no further contributions");
    }

    @Test
    void corpusReducesButDoesNotEliminateContribution() {
        // ₹10L corpus grows to ~₹33L; remaining ₹67L gap needs PMT ≈ 67e5 × 0.01 / 2.30039
        BigDecimal pmt = GoalAnalyzerService.requiredMonthlyContribution(
                BigDecimal.valueOf(10_000_000), BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(12), 120);

        assertTrue(pmt.doubleValue() > 0 && pmt.doubleValue() < 43_471,
                "a head-start corpus must lower the monthly requirement below the zero-corpus PMT");
    }

    @Test
    void expectedProgress_isConvexNotLinearForCompoundingGoals() {
        // Halfway through a 10-year goal at 12% p.a., the annuity has accumulated
        // (1.12^5 − 1)/(1.12^10 − 1) = 0.7623/2.1058 ≈ 36.2% of the target —
        // far below the linear 50%. A linear expectation would wrongly flag
        // this goal OFF_TRACK.
        double pct = GoalAnalyzerService.expectedProgressPct(60, 120, BigDecimal.valueOf(12));
        assertEquals(36.2, pct, 0.05);
        assertTrue(pct < 50.0, "compounding goals must be expected behind the linear line early");
    }

    @Test
    void expectedProgress_degeneratesToLinearWithoutReturnAssumption() {
        assertEquals(50.0, GoalAnalyzerService.expectedProgressPct(6, 12, null), 1e-9);
        assertEquals(50.0, GoalAnalyzerService.expectedProgressPct(6, 12, BigDecimal.ZERO), 1e-9);
    }
}
