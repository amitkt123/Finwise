package org.amit.finwise.cfo.service.analytics.options;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.springframework.stereotype.Service;

/**
 * Black-Scholes-Merton European option pricing and Greeks (BPR-4).
 *
 * Computable today from EOD F&O bhavcopy: spot = EOD close, strike/expiry from the
 * contract, risk-free {@code r} from the FBIL/G-sec curve, dividend yield {@code q}
 * default 0 (or an index dividend yield where known). A live intraday vol surface is
 * out of scope (needs a licensed tick feed) — this is the EOD analytic.
 *
 *   d1 = [ln(S/K) + (r − q + σ²/2)·T] / (σ·√T)
 *   d2 = d1 − σ·√T
 *   Call = S·e^(−qT)·N(d1) − K·e^(−rT)·N(d2)
 *   Put  = K·e^(−rT)·N(−d2) − S·e^(−qT)·N(−d1)
 *
 * All Greeks are returned in natural units: vega per 1.00 change in σ (i.e. per
 * 100 vol points), theta per year, rho per 1.00 change in r. Callers scale to
 * per-1%/per-day as needed.
 */
@Service
public class BlackScholesService {

    public enum OptionType { CALL, PUT }

    /** Standard normal — N(·) via cumulativeProbability, φ(·) via density. */
    private static final NormalDistribution N = new NormalDistribution();

    /**
     * @param spot   underlying price S (> 0)
     * @param strike strike K (> 0)
     * @param tYears time to expiry in years (> 0)
     * @param rate   continuously-compounded risk-free rate r (annual)
     * @param dividendYield continuous dividend yield q (annual; 0 if unknown)
     * @param vol    volatility σ (annual; > 0)
     */
    public record Inputs(OptionType type, double spot, double strike, double tYears,
                         double rate, double dividendYield, double vol) {}

    public record Greeks(
            double price,
            double delta,   // ∂V/∂S
            double gamma,   // ∂²V/∂S²
            double vega,    // ∂V/∂σ  (per 1.00 vol)
            double theta,   // ∂V/∂t  (per year; negative = time decay)
            double rho      // ∂V/∂r  (per 1.00 rate)
    ) {}

    /** European option price. Returns intrinsic value at the degenerate T→0 / σ→0 limit. */
    public double price(Inputs in) {
        if (!valid(in)) return intrinsic(in);
        double sqrtT = Math.sqrt(in.tYears());
        double d1 = d1(in, sqrtT);
        double d2 = d1 - in.vol() * sqrtT;
        double dfR = Math.exp(-in.rate() * in.tYears());
        double dfQ = Math.exp(-in.dividendYield() * in.tYears());
        if (in.type() == OptionType.CALL) {
            return in.spot() * dfQ * N.cumulativeProbability(d1)
                    - in.strike() * dfR * N.cumulativeProbability(d2);
        }
        return in.strike() * dfR * N.cumulativeProbability(-d2)
                - in.spot() * dfQ * N.cumulativeProbability(-d1);
    }

    /** Full Greeks bundle plus the price. */
    public Greeks greeks(Inputs in) {
        if (!valid(in)) {
            return new Greeks(intrinsic(in), Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double S = in.spot(), K = in.strike(), T = in.tYears(), r = in.rate(), q = in.dividendYield();
        double sqrtT = Math.sqrt(T);
        double d1 = d1(in, sqrtT);
        double d2 = d1 - in.vol() * sqrtT;
        double dfR = Math.exp(-r * T);
        double dfQ = Math.exp(-q * T);
        double pdfD1 = N.density(d1);

        double price = price(in);
        double gamma = dfQ * pdfD1 / (S * in.vol() * sqrtT);
        double vega = S * dfQ * pdfD1 * sqrtT;     // per 1.00 vol

        double delta, theta, rho;
        if (in.type() == OptionType.CALL) {
            delta = dfQ * N.cumulativeProbability(d1);
            theta = -(S * dfQ * pdfD1 * in.vol()) / (2 * sqrtT)
                    - r * K * dfR * N.cumulativeProbability(d2)
                    + q * S * dfQ * N.cumulativeProbability(d1);
            rho = K * T * dfR * N.cumulativeProbability(d2);
        } else {
            delta = -dfQ * N.cumulativeProbability(-d1);
            theta = -(S * dfQ * pdfD1 * in.vol()) / (2 * sqrtT)
                    + r * K * dfR * N.cumulativeProbability(-d2)
                    - q * S * dfQ * N.cumulativeProbability(-d1);
            rho = -K * T * dfR * N.cumulativeProbability(-d2);
        }
        return new Greeks(price, delta, gamma, vega, theta, rho);
    }

    private static double d1(Inputs in, double sqrtT) {
        return (Math.log(in.spot() / in.strike())
                + (in.rate() - in.dividendYield() + 0.5 * in.vol() * in.vol()) * in.tYears())
                / (in.vol() * sqrtT);
    }

    private static boolean valid(Inputs in) {
        return in.spot() > 0 && in.strike() > 0 && in.tYears() > 0 && in.vol() > 0;
    }

    /** Discounted intrinsic value — the no-vol / no-time payoff floor. */
    static double intrinsic(Inputs in) {
        double payoff = in.type() == OptionType.CALL
                ? Math.max(in.spot() - in.strike(), 0)
                : Math.max(in.strike() - in.spot(), 0);
        return payoff;
    }
}
