package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known-answer tests for the Hazen percentile rank ((i − 0.5)/n).
 */
class PercentileUtilTest {

    @Test
    void matchesHandComputedHazenRanks() {
        double[] sample = {10, 20, 30, 40, 50};
        // 30 is the 3rd of 5 → (3 − 0.5)/5 = 50%
        assertEquals(50.0, PercentileUtil.hazenPercentile(30, sample), 1e-12);
        // 10 is the 1st of 5 → (1 − 0.5)/5 = 10%
        assertEquals(10.0, PercentileUtil.hazenPercentile(10, sample), 1e-12);
        // 50 is the 5th of 5 → (5 − 0.5)/5 = 90%
        assertEquals(90.0, PercentileUtil.hazenPercentile(50, sample), 1e-12);
    }

    @Test
    void tiesTakeTheAverageRank() {
        double[] sample = {10, 20, 20, 20, 30};
        // 20 occupies ranks 2,3,4 → average rank 3 → (3 − 0.5)/5 = 50%
        assertEquals(50.0, PercentileUtil.hazenPercentile(20, sample), 1e-12);
    }

    @Test
    void valueOutsideSampleStillRanks() {
        double[] sample = {10, 20, 30};
        // 25 sits above 2 of 3 → rank 2.5 → (2.5 − 0.5)/3 ≈ 66.67%
        assertEquals(200.0 / 3.0, PercentileUtil.hazenPercentile(25, sample), 1e-9);
        assertTrue(Double.isNaN(PercentileUtil.hazenPercentile(1, new double[0])));
    }
}
