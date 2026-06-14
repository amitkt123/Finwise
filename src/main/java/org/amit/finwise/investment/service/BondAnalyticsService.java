package org.amit.finwise.investment.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fixed-coupon bond analytics (BPR-7): YTM, Macaulay & modified duration, convexity,
 * DV01, and accrued interest. Closes the "13 asset types, one of them (BOND) is a
 * stub" gap with clean instrument math.
 *
 * Computable today from clean instrument inputs (coupon, face, frequency, settlement,
 * maturity, clean/dirty price) — user-entered or off the existing G-sec curve. Full
 * corporate-credit depth (credit curves, OAS, ratings transitions) needs licensed
 * credit data and is out of scope.
 *
 * Conventions: cash flows are placed at whole coupon periods measured back from
 * maturity; the fractional first period uses a day-count basis for accrued interest.
 * Yields are nominal annual, compounded at the coupon {@code frequency}.
 */
@Slf4j
@Service
public class BondAnalyticsService {

    /** Day-count basis for accrued interest. */
    public enum DayCount { THIRTY_360, ACT_ACT }

    /**
     * @param faceValue       redemption (par) value, e.g. 100 or 1000
     * @param annualCouponRate annual coupon as a fraction (0.07 = 7%)
     * @param frequency       coupon payments per year (1, 2, 4)
     * @param settlement      valuation/settlement date
     * @param maturity        final redemption date
     * @param cleanPrice      quoted price excluding accrued interest (same units as face)
     * @param dayCount        accrued-interest day-count basis
     */
    public record BondSpec(double faceValue, double annualCouponRate, int frequency,
                           LocalDate settlement, LocalDate maturity,
                           double cleanPrice, DayCount dayCount) {}

    public record BondAnalytics(
            double ytm,                 // yield to maturity (annual, compounded at frequency)
            double dirtyPrice,          // clean + accrued
            double accruedInterest,
            double macaulayDuration,    // years
            double modifiedDuration,    // years; −(1/P)·dP/dy
            double convexity,           // years²
            double dv01,                // price change per 1bp yield move (per faceValue units)
            int cashFlowCount,
            boolean converged,
            String method               // "newton" | "bisection" | "none"
    ) {}

    private static final double SEED = 0.06;
    private static final int NEWTON_MAX_ITERS = 100;
    private static final double PRICE_TOL = 1e-8;
    private static final double DERIV_FLOOR = 1e-10;
    private static final double Y_LOW = -0.9999;
    private static final double Y_HIGH = 2.0;       // 200% yield ceiling
    private static final int BISECTION_MAX_ITERS = 200;

    /** A dated cash flow (coupon and/or redemption) in years-from-settlement. */
    private record Flow(double tYears, double amount) {}

    /**
     * Full analytics for a fixed-coupon bond. Empty when the spec is degenerate
     * (non-positive price, settlement on/after maturity, or no remaining cash flows)
     * or the YTM solver fails to bracket a root — never a garbage yield.
     */
    public Optional<BondAnalytics> analyze(BondSpec b) {
        if (b == null || b.cleanPrice() <= 0 || b.faceValue() <= 0 || b.frequency() <= 0
                || b.settlement() == null || b.maturity() == null
                || !b.maturity().isAfter(b.settlement())) {
            return Optional.empty();
        }

        List<Flow> flows = buildFlows(b);
        if (flows.isEmpty()) return Optional.empty();

        double accrued = accruedInterest(b);
        double dirty = b.cleanPrice() + accrued;

        Optional<Double> ytmOpt = solveYtm(flows, dirty, b.frequency());
        if (ytmOpt.isEmpty()) return Optional.empty();
        double ytm = ytmOpt.get();
        String method = newtonWouldConverge(flows, dirty, b.frequency()) ? "newton" : "bisection";

        // Duration / convexity at the solved yield (per-period discounting).
        int m = b.frequency();
        double yPer = ytm / m;
        double price = 0, dMac = 0, conv = 0;
        for (Flow f : flows) {
            double periods = f.tYears() * m;
            double df = Math.pow(1 + yPer, -periods);
            double pv = f.amount() * df;
            price += pv;
            dMac += f.tYears() * pv;                       // time-weighted PV (years)
            conv += pv * f.tYears() * (f.tYears() + 1.0 / m); // see note below
        }
        double macaulay = price > 0 ? dMac / price : Double.NaN;
        double modified = macaulay / (1 + yPer);
        // Convexity = (1/P)·d²P/dy². With per-period discounting the closed form is
        // Σ PVᵢ·tᵢ·(tᵢ + 1/m) / [P·(1+y/m)²].
        double convexity = price > 0 ? conv / (price * (1 + yPer) * (1 + yPer)) : Double.NaN;
        double dv01 = modified * dirty * 1e-4;             // price move per 1bp

        return Optional.of(new BondAnalytics(
                ytm, dirty, accrued, macaulay, modified, convexity, dv01,
                flows.size(), true, method));
    }

    // ── Cash flows ─────────────────────────────────────────────────────────────

    /**
     * Coupon dates stepped back from maturity by whole periods; only those strictly
     * after settlement remain. Redemption (face) is added to the final coupon.
     */
    private List<Flow> buildFlows(BondSpec b) {
        int m = b.frequency();
        double coupon = b.faceValue() * b.annualCouponRate() / m;
        int monthsPerPeriod = 12 / m;

        // Collect coupon dates from maturity backwards until on/before settlement.
        List<LocalDate> dates = new ArrayList<>();
        LocalDate d = b.maturity();
        while (d.isAfter(b.settlement())) {
            dates.add(d);
            d = d.minusMonths(monthsPerPeriod);
        }
        if (dates.isEmpty()) return List.of();

        List<Flow> flows = new ArrayList<>();
        for (LocalDate cd : dates) {
            double t = yearFraction(b.settlement(), cd, b.dayCount());
            double amt = coupon + (cd.equals(b.maturity()) ? b.faceValue() : 0.0);
            flows.add(new Flow(t, amt));
        }
        flows.sort((x, y) -> Double.compare(x.tYears(), y.tYears()));
        return flows;
    }

    // ── Accrued interest ───────────────────────────────────────────────────────

    /**
     * Accrued interest from the last coupon date to settlement, as a fraction of the
     * coupon, under the chosen day-count. The previous coupon date is maturity stepped
     * back by whole periods to just on/before settlement.
     */
    double accruedInterest(BondSpec b) {
        int m = b.frequency();
        int monthsPerPeriod = 12 / m;
        double couponAmt = b.faceValue() * b.annualCouponRate() / m;

        LocalDate next = b.maturity();
        while (next.minusMonths(monthsPerPeriod).isAfter(b.settlement())) {
            next = next.minusMonths(monthsPerPeriod);
        }
        LocalDate prev = next.minusMonths(monthsPerPeriod);
        if (!prev.isBefore(b.settlement()) && !prev.equals(b.settlement())) {
            // settlement before the first accrual period — no accrued interest
            return 0.0;
        }

        double accrualFraction = switch (b.dayCount()) {
            case THIRTY_360 -> days360(prev, b.settlement()) / (double) days360(prev, next);
            case ACT_ACT -> (double) ChronoUnit.DAYS.between(prev, b.settlement())
                    / ChronoUnit.DAYS.between(prev, next);
        };
        return couponAmt * Math.max(0.0, Math.min(1.0, accrualFraction));
    }

    // ── YTM solver: Newton with bisection fallback ─────────────────────────────

    private Optional<Double> solveYtm(List<Flow> flows, double dirtyPrice, int m) {
        double y = SEED;
        for (int i = 0; i < NEWTON_MAX_ITERS; i++) {
            double diff = pv(flows, y, m) - dirtyPrice;
            if (Math.abs(diff) < PRICE_TOL) return Optional.of(y);
            double d = dPv(flows, y, m);
            if (Math.abs(d) < DERIV_FLOOR) break;
            double next = y - diff / d;
            if (next <= Y_LOW || next >= Y_HIGH || Double.isNaN(next)) break;
            if (Math.abs(next - y) < PRICE_TOL) return Optional.of(next);
            y = next;
        }
        return bisect(flows, dirtyPrice, m);
    }

    private boolean newtonWouldConverge(List<Flow> flows, double dirtyPrice, int m) {
        double y = SEED;
        for (int i = 0; i < NEWTON_MAX_ITERS; i++) {
            double diff = pv(flows, y, m) - dirtyPrice;
            if (Math.abs(diff) < PRICE_TOL) return true;
            double d = dPv(flows, y, m);
            if (Math.abs(d) < DERIV_FLOOR) return false;
            double next = y - diff / d;
            if (next <= Y_LOW || next >= Y_HIGH || Double.isNaN(next)) return false;
            if (Math.abs(next - y) < PRICE_TOL) return true;
            y = next;
        }
        return false;
    }

    private Optional<Double> bisect(List<Flow> flows, double dirtyPrice, int m) {
        double lo = Y_LOW, hi = Y_HIGH;
        double fLo = pv(flows, lo, m) - dirtyPrice;
        double fHi = pv(flows, hi, m) - dirtyPrice;
        if (fLo * fHi > 0) return Optional.empty();
        for (int i = 0; i < BISECTION_MAX_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = pv(flows, mid, m) - dirtyPrice;
            if (Math.abs(fMid) < PRICE_TOL || (hi - lo) < 1e-12) return Optional.of(mid);
            if (fLo * fMid < 0) hi = mid;
            else { lo = mid; fLo = fMid; }
        }
        return Optional.of(0.5 * (lo + hi));
    }

    /** Present value of the cash flows at annual yield {@code y}, compounded {@code m}/yr. */
    private static double pv(List<Flow> flows, double y, int m) {
        double yPer = y / m;
        double sum = 0;
        for (Flow f : flows) sum += f.amount() * Math.pow(1 + yPer, -f.tYears() * m);
        return sum;
    }

    /** dP/dy of the present value (analytic), for Newton. */
    private static double dPv(List<Flow> flows, double y, int m) {
        double yPer = y / m;
        double sum = 0;
        for (Flow f : flows) {
            double periods = f.tYears() * m;
            sum += -periods / m * f.amount() * Math.pow(1 + yPer, -periods - 1);
        }
        return sum;
    }

    // ── Day counts ─────────────────────────────────────────────────────────────

    static double yearFraction(LocalDate from, LocalDate to, DayCount dc) {
        return switch (dc) {
            case THIRTY_360 -> days360(from, to) / 360.0;
            case ACT_ACT -> ChronoUnit.DAYS.between(from, to) / 365.0;
        };
    }

    /** US (NASD) 30/360 day count. */
    static int days360(LocalDate start, LocalDate end) {
        int d1 = start.getDayOfMonth();
        int d2 = end.getDayOfMonth();
        if (d1 == 31) d1 = 30;
        if (d2 == 31 && d1 == 30) d2 = 30;
        return (end.getYear() - start.getYear()) * 360
                + (end.getMonthValue() - start.getMonthValue()) * 30
                + (d2 - d1);
    }
}
