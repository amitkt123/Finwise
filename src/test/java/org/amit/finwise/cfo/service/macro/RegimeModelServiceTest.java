package org.amit.finwise.cfo.service.macro;

import org.amit.finwise.cfo.service.macro.RegimeModelService.RegimeResult;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EM recovery test for the 2-state Gaussian HMM (BPR-8): on a synthetic series with a
 * planted high-vol crisis window, the fit recovers the two volatilities and the
 * smoothed crisis probability spikes inside the planted window.
 */
class RegimeModelServiceTest {

    private final RegimeModelService svc = new RegimeModelService();

    @Test
    void recoversTwoRegimesAndLocatesCrisisWindow() {
        Random rnd = new Random(20260614L);
        int calmLen = 250, crisisLen = 60;
        double calmVol = 0.008, crisisVol = 0.030;   // 0.8% vs 3% daily
        int T = calmLen + crisisLen + calmLen;
        double[] r = new double[T];

        int crisisStart = calmLen, crisisEnd = calmLen + crisisLen;
        for (int t = 0; t < T; t++) {
            boolean inCrisis = t >= crisisStart && t < crisisEnd;
            double vol = inCrisis ? crisisVol : calmVol;
            double mean = inCrisis ? -0.002 : 0.0005;   // crisis drifts down
            r[t] = mean + vol * rnd.nextGaussian();
        }

        RegimeResult res = svc.fit(r).orElseThrow();

        // Volatilities recovered within tolerance.
        assertEquals(calmVol, res.calmDailyVol(), 0.004, "calm vol recovered");
        assertEquals(crisisVol, res.crisisDailyVol(), 0.008, "crisis vol recovered");
        assertTrue(res.crisisDailyVol() > res.calmDailyVol() * 2, "crisis state is clearly higher-vol");

        // Smoothed crisis probability should be high inside the window, low outside.
        double[] p = res.smoothedCrisisProb();
        double avgInside = avg(p, crisisStart, crisisEnd);
        double avgCalmBefore = avg(p, 0, calmLen);
        assertTrue(avgInside > 0.7, "crisis prob high inside the planted window, got " + avgInside);
        assertTrue(avgCalmBefore < 0.3, "crisis prob low in the calm window, got " + avgCalmBefore);

        // Expected crisis spell is finite and positive.
        assertTrue(res.expectedCrisisDurationDays() > 1.0);
    }

    @Test
    void homogeneousSeriesYieldsPoorlySeparatedStates() {
        // With no planted regime the two states are not separable: the "crisis" vol is
        // not materially above the calm vol, so the regime signal is degenerate (and the
        // crisis label arbitrary). The honest invariant is low vol-separation, not a
        // particular probability — a consumer should gate on separation before trusting it.
        Random rnd = new Random(3L);
        double[] r = new double[400];
        for (int t = 0; t < r.length; t++) r[t] = 0.0005 + 0.008 * rnd.nextGaussian();

        RegimeResult res = svc.fit(r).orElseThrow();
        double separation = res.crisisDailyVol() / res.calmDailyVol();
        assertTrue(separation < 2.0,
                "homogeneous series should not produce well-separated regimes, ratio=" + separation);
    }

    @Test
    void tooFewObservationsReturnsEmpty() {
        assertTrue(svc.fit(new double[10]).isEmpty());
        assertTrue(svc.fit(null).isEmpty());
    }

    private static double avg(double[] a, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += a[i];
        return s / (to - from);
    }
}
