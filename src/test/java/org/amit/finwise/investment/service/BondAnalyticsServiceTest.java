package org.amit.finwise.investment.service;

import org.amit.finwise.investment.service.BondAnalyticsService.BondAnalytics;
import org.amit.finwise.investment.service.BondAnalyticsService.BondSpec;
import org.amit.finwise.investment.service.BondAnalyticsService.DayCount;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-value tests for fixed-coupon bond math (BPR-7): par-bond YTM = coupon,
 * YTM round-trip, closed-form zero-coupon duration/convexity, and day-count accrual.
 */
class BondAnalyticsServiceTest {

    private final BondAnalyticsService svc = new BondAnalyticsService();

    /** Manual present value at annual yield y compounded m/yr, cash flows = annual coupons + face. */
    private static double priceAnnual(double face, double couponRate, int years, double y) {
        double pv = 0;
        for (int t = 1; t <= years; t++) {
            double cf = face * couponRate + (t == years ? face : 0);
            pv += cf / Math.pow(1 + y, t);
        }
        return pv;
    }

    @Test
    void parBondYtmEqualsCouponRate() {
        // Priced at par on a coupon date → YTM equals the coupon rate.
        BondSpec b = new BondSpec(100, 0.07, 1,
                LocalDate.of(2026, 6, 14), LocalDate.of(2031, 6, 14),
                100.0, DayCount.THIRTY_360);
        BondAnalytics a = svc.analyze(b).orElseThrow();
        assertEquals(0.07, a.ytm(), 1e-6);
        assertEquals(0.0, a.accruedInterest(), 1e-9, "settlement on a coupon date → no accrual");
        assertTrue(a.converged());
    }

    @Test
    void ytmRoundTripFromKnownPrice() {
        double trueYtm = 0.085;
        double clean = priceAnnual(100, 0.06, 7, trueYtm);
        BondSpec b = new BondSpec(100, 0.06, 1,
                LocalDate.of(2026, 6, 14), LocalDate.of(2033, 6, 14),
                clean, DayCount.THIRTY_360);
        BondAnalytics a = svc.analyze(b).orElseThrow();
        assertEquals(trueYtm, a.ytm(), 1e-5);
    }

    @Test
    void discountBondYtmExceedsCoupon() {
        // Priced below par → YTM above coupon.
        double clean = priceAnnual(100, 0.05, 5, 0.08);   // < 100
        assertTrue(clean < 100);
        BondSpec b = new BondSpec(100, 0.05, 1,
                LocalDate.of(2026, 6, 14), LocalDate.of(2031, 6, 14),
                clean, DayCount.THIRTY_360);
        assertEquals(0.08, svc.analyze(b).orElseThrow().ytm(), 1e-5);
    }

    @Test
    void zeroCouponDurationAndConvexityMatchClosedForm() {
        // Annual zero (coupon 0): single cash flow at T. Macaulay = T, modified = T/(1+y),
        // convexity = T(T+1)/(1+y)². Price the zero at a known yield first.
        int T = 5;
        double y = 0.06;
        double clean = 100 / Math.pow(1 + y, T);
        BondSpec b = new BondSpec(100, 0.0, 1,
                LocalDate.of(2026, 6, 14), LocalDate.of(2031, 6, 14),
                clean, DayCount.THIRTY_360);
        BondAnalytics a = svc.analyze(b).orElseThrow();

        assertEquals(y, a.ytm(), 1e-6);
        assertEquals(T, a.macaulayDuration(), 1e-4);
        assertEquals(T / (1 + y), a.modifiedDuration(), 1e-4);
        assertEquals((double) T * (T + 1) / Math.pow(1 + y, 2), a.convexity(), 1e-3);
    }

    @Test
    void dv01ApproximatesPriceMoveForOneBp() {
        BondSpec b = new BondSpec(100, 0.07, 2,
                LocalDate.of(2026, 6, 14), LocalDate.of(2036, 6, 14),
                95.0, DayCount.THIRTY_360);
        BondAnalytics a = svc.analyze(b).orElseThrow();
        // DV01 = modified duration × dirty price × 1bp; must be positive and small.
        assertTrue(a.dv01() > 0);
        assertEquals(a.modifiedDuration() * a.dirtyPrice() * 1e-4, a.dv01(), 1e-12);
    }

    @Test
    void accruedInterestIsHalfCouponAtMidPeriod() {
        // Semi-annual 8% bond, settlement exactly mid-way between coupons (3 months in)
        // under 30/360 → accrued ≈ half of the half-yearly coupon.
        BondSpec b = new BondSpec(100, 0.08, 2,
                LocalDate.of(2026, 9, 14), LocalDate.of(2030, 6, 14),
                100.0, DayCount.THIRTY_360);
        double accrued = svc.accruedInterest(b);
        // half-yearly coupon = 4.0; 3 months of a 6-month period = 0.5 → ~2.0
        assertEquals(2.0, accrued, 0.05);
    }

    @Test
    void degenerateSpecReturnsEmpty() {
        assertTrue(svc.analyze(new BondSpec(100, 0.07, 1,
                LocalDate.of(2031, 6, 14), LocalDate.of(2026, 6, 14), 100, DayCount.ACT_ACT))
                .isEmpty(), "settlement after maturity");
        assertTrue(svc.analyze(new BondSpec(100, 0.07, 1,
                LocalDate.of(2026, 6, 14), LocalDate.of(2031, 6, 14), 0, DayCount.ACT_ACT))
                .isEmpty(), "non-positive price");
    }
}
