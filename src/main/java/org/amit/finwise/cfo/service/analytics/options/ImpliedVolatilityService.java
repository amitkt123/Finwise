package org.amit.finwise.cfo.service.analytics.options;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Inputs;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Implied volatility — invert an EOD option price to σ (BPR-4).
 *
 * Newton on vega seeded at 0.30, with a bisection fallback on [1e-4, 5.0] when
 * Newton diverges or vega collapses (deep ITM/OTM). No-arbitrage bounds are checked
 * first: a price outside [discounted intrinsic, forward bound] has no positive-σ
 * root and returns empty (flagged by the caller).
 */
@Service
@RequiredArgsConstructor
public class ImpliedVolatilityService {

    private final BlackScholesService bsm;

    private static final double SEED = 0.30;
    private static final int NEWTON_MAX_ITERS = 100;
    private static final double PRICE_TOL = 1e-8;
    private static final double VEGA_FLOOR = 1e-8;
    private static final double VOL_LOW = 1e-4;
    private static final double VOL_HIGH = 5.0;
    private static final int BISECTION_MAX_ITERS = 200;

    public record ImpliedVol(double vol, boolean converged, String method) {}

    /**
     * Solve for σ given an observed option price. Empty when the price violates the
     * no-arbitrage bounds for any σ ≥ 0 (a stale/erroneous EOD row), so a garbage
     * root is never returned.
     */
    public Optional<ImpliedVol> impliedVol(OptionType type, double marketPrice, double spot,
                                           double strike, double tYears, double rate,
                                           double dividendYield) {
        if (marketPrice <= 0 || spot <= 0 || strike <= 0 || tYears <= 0) return Optional.empty();

        double dfR = Math.exp(-rate * tYears);
        double dfQ = Math.exp(-dividendYield * tYears);
        // No-arbitrage price band for a European option as σ ranges over (0, ∞).
        double lower, upper;
        if (type == OptionType.CALL) {
            lower = Math.max(spot * dfQ - strike * dfR, 0.0);
            upper = spot * dfQ;
        } else {
            lower = Math.max(strike * dfR - spot * dfQ, 0.0);
            upper = strike * dfR;
        }
        // Allow a tiny tolerance for rounding at the boundary.
        if (marketPrice < lower - 1e-6 || marketPrice > upper + 1e-6) return Optional.empty();

        // --- Newton on vega ---
        double sigma = SEED;
        for (int i = 0; i < NEWTON_MAX_ITERS; i++) {
            Inputs in = mk(type, spot, strike, tYears, rate, dividendYield, sigma);
            double diff = bsm.price(in) - marketPrice;
            if (Math.abs(diff) < PRICE_TOL) {
                return Optional.of(new ImpliedVol(sigma, true, "newton"));
            }
            double vega = bsm.greeks(in).vega();
            if (vega < VEGA_FLOOR || Double.isNaN(vega)) break; // flat → bisection
            double next = sigma - diff / vega;
            if (next <= VOL_LOW || next >= VOL_HIGH || Double.isNaN(next)) break;
            if (Math.abs(next - sigma) < 1e-10) {
                return Optional.of(new ImpliedVol(next, true, "newton"));
            }
            sigma = next;
        }

        // --- Bisection fallback on a guaranteed sign-change bracket ---
        return bisect(type, marketPrice, spot, strike, tYears, rate, dividendYield);
    }

    private Optional<ImpliedVol> bisect(OptionType type, double marketPrice, double spot,
                                        double strike, double tYears, double rate, double q) {
        double lo = VOL_LOW, hi = VOL_HIGH;
        double fLo = bsm.price(mk(type, spot, strike, tYears, rate, q, lo)) - marketPrice;
        double fHi = bsm.price(mk(type, spot, strike, tYears, rate, q, hi)) - marketPrice;
        if (fLo * fHi > 0) return Optional.empty(); // no root in band (shouldn't happen post-bounds)
        for (int i = 0; i < BISECTION_MAX_ITERS; i++) {
            double mid = 0.5 * (lo + hi);
            double fMid = bsm.price(mk(type, spot, strike, tYears, rate, q, mid)) - marketPrice;
            if (Math.abs(fMid) < PRICE_TOL || (hi - lo) < 1e-10) {
                return Optional.of(new ImpliedVol(mid, true, "bisection"));
            }
            if (fLo * fMid < 0) hi = mid;
            else { lo = mid; fLo = fMid; }
        }
        return Optional.of(new ImpliedVol(0.5 * (lo + hi), true, "bisection"));
    }

    private static Inputs mk(OptionType type, double spot, double strike, double tYears,
                             double rate, double q, double vol) {
        return new Inputs(type, spot, strike, tYears, rate, q, vol);
    }
}
