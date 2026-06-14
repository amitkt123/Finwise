package org.amit.finwise.cfo.service.analytics.options;

import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Greeks;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Inputs;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-value tests for Black-Scholes-Merton pricing and Greeks, checked against
 * a published textbook example (Hull), put-call parity, and finite-difference bumps.
 */
class BlackScholesServiceTest {

    private final BlackScholesService bsm = new BlackScholesService();

    private static Inputs call(double s, double k, double t, double r, double q, double v) {
        return new Inputs(OptionType.CALL, s, k, t, r, q, v);
    }

    private static Inputs put(double s, double k, double t, double r, double q, double v) {
        return new Inputs(OptionType.PUT, s, k, t, r, q, v);
    }

    @Test
    void callPriceMatchesHullTextbookExample() {
        // Hull, Options Futures & Other Derivatives: S=42, K=40, r=0.10, σ=0.20,
        // T=0.5, q=0 → call ≈ 4.759, put ≈ 0.808.
        assertEquals(4.759, bsm.price(call(42, 40, 0.5, 0.10, 0, 0.20)), 1e-3);
        assertEquals(0.808, bsm.price(put(42, 40, 0.5, 0.10, 0, 0.20)), 1e-3);
    }

    @Test
    void putCallParityHolds() {
        // C − P = S·e^(−qT) − K·e^(−rT)
        double S = 100, K = 95, T = 0.75, r = 0.05, q = 0.02, v = 0.25;
        double c = bsm.price(call(S, K, T, r, q, v));
        double p = bsm.price(put(S, K, T, r, q, v));
        double lhs = c - p;
        double rhs = S * Math.exp(-q * T) - K * Math.exp(-r * T);
        assertEquals(rhs, lhs, 1e-9);
    }

    @Test
    void deltaMatchesFiniteDifference() {
        Inputs base = call(100, 100, 1.0, 0.03, 0.0, 0.20);
        double h = 1e-4;
        double up = bsm.price(call(100 + h, 100, 1.0, 0.03, 0.0, 0.20));
        double down = bsm.price(call(100 - h, 100, 1.0, 0.03, 0.0, 0.20));
        double fdDelta = (up - down) / (2 * h);
        assertEquals(fdDelta, bsm.greeks(base).delta(), 1e-5);
    }

    @Test
    void gammaMatchesFiniteDifference() {
        Inputs base = call(100, 100, 1.0, 0.03, 0.0, 0.20);
        double h = 1e-2;
        double up = bsm.price(call(100 + h, 100, 1.0, 0.03, 0.0, 0.20));
        double mid = bsm.price(base);
        double down = bsm.price(call(100 - h, 100, 1.0, 0.03, 0.0, 0.20));
        double fdGamma = (up - 2 * mid + down) / (h * h);
        assertEquals(fdGamma, bsm.greeks(base).gamma(), 1e-4);
    }

    @Test
    void vegaMatchesFiniteDifference() {
        Inputs base = call(100, 105, 0.5, 0.03, 0.0, 0.22);
        double h = 1e-5;
        double up = bsm.price(call(100, 105, 0.5, 0.03, 0.0, 0.22 + h));
        double down = bsm.price(call(100, 105, 0.5, 0.03, 0.0, 0.22 - h));
        double fdVega = (up - down) / (2 * h);
        assertEquals(fdVega, bsm.greeks(base).vega(), 1e-3);
    }

    @Test
    void thetaMatchesFiniteDifference() {
        // theta = ∂V/∂t = −∂V/∂T. Bump T and negate.
        Inputs base = call(100, 100, 1.0, 0.03, 0.0, 0.20);
        double h = 1e-5;
        double tUp = bsm.price(call(100, 100, 1.0 + h, 0.03, 0.0, 0.20));
        double tDown = bsm.price(call(100, 100, 1.0 - h, 0.03, 0.0, 0.20));
        double fdTheta = -(tUp - tDown) / (2 * h);
        assertEquals(fdTheta, bsm.greeks(base).theta(), 1e-2);
    }

    @Test
    void putDeltaIsCallDeltaMinusDividendDiscount() {
        // Δcall − Δput = e^(−qT)
        double q = 0.04, T = 0.6;
        Greeks c = bsm.greeks(call(100, 100, T, 0.03, q, 0.2));
        Greeks p = bsm.greeks(put(100, 100, T, 0.03, q, 0.2));
        assertEquals(Math.exp(-q * T), c.delta() - p.delta(), 1e-9);
    }

    @Test
    void degenerateInputsReturnIntrinsic() {
        // Zero time → intrinsic value, Greeks NaN-flagged.
        assertEquals(5.0, bsm.price(call(105, 100, 0.0, 0.03, 0.0, 0.2)), 1e-12);
        assertTrue(Double.isNaN(bsm.greeks(call(105, 100, 0.0, 0.03, 0.0, 0.2)).delta()));
    }
}
