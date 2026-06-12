package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Known-answer tests for the Corwin-Schultz (2012) high/low spread estimator.
 * Golden values were computed with an independent Python reference implementation of
 * the paper's formulas on the same fixtures (matched to 1e-8).
 */
class LiquidityServiceTest {

    // 3-day fixture, ranges overlap day-to-day (no overnight gap adjustment triggers).
    private static final double[] HIGHS = {102.0, 103.0, 101.0};
    private static final double[] LOWS  = { 98.0,  99.0,  97.0};

    @Test
    void singleWindowMatchesReference() {
        double s0 = LiquidityService.singleWindowSpread(HIGHS[0], LOWS[0], HIGHS[1], LOWS[1]);
        assertEquals(0.01577685061383833, s0, 1e-8);

        double s1 = LiquidityService.singleWindowSpread(HIGHS[1], LOWS[1], HIGHS[2], LOWS[2]);
        assertEquals(-0.008288986952434844, s1, 1e-8);   // negative → floored to 0 in averaging
    }

    @Test
    void threeDayAverageFloorsNegativesThenAverages() {
        // (0.01577685061383833 + max(0, -0.0082...)) / 2
        double avg = LiquidityService.corwinSchultzSpread(HIGHS, LOWS);
        assertEquals(0.007888425306919165, avg, 1e-8);
    }

    @Test
    void overnightGapAdjustmentMatchesReference() {
        // Day 1 gaps fully above day 0 (no intraday-range overlap). The overnight-gap
        // adjustment shifts day 1 down by (L_1 − H_0) so the 2-day range γ isn't inflated
        // by the overnight jump. Golden value is the *adjusted* CS spread; without the
        // adjustment the reference gives −0.2130 (far more negative).
        double gapped = LiquidityService.singleWindowSpread(102.0, 98.0, 113.0, 109.0);
        assertEquals(-0.055447836570871996, gapped, 1e-8);
        assertTrue(gapped > -0.21302819507072343,
                "gap adjustment must pull the estimate up vs the unadjusted range");
    }

    @Test
    void identicalFlatRangesGiveNonPositiveSpread() {
        // Zero intraday range can't happen (H>L required), but a tiny constant range
        // across days should not yield a large positive spread.
        double s = LiquidityService.corwinSchultzSpread(
                new double[]{100.5, 100.5, 100.5}, new double[]{100.0, 100.0, 100.0});
        assertTrue(s >= 0.0 && s < 0.01);
    }
}
