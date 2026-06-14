package org.amit.finwise.cfo.service.analytics.options;

import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Inputs;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.amit.finwise.cfo.service.analytics.options.ImpliedVolatilityService.ImpliedVol;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IV round-trip tests: price→σ→price must recover the input σ, plus no-arbitrage
 * rejection of out-of-band prices.
 */
class ImpliedVolatilityServiceTest {

    private final BlackScholesService bsm = new BlackScholesService();
    private final ImpliedVolatilityService iv = new ImpliedVolatilityService(bsm);

    @Test
    void roundTripRecoversAtmVol() {
        double s = 100, k = 100, t = 0.5, r = 0.04, q = 0.0, trueVol = 0.27;
        double price = bsm.price(new Inputs(OptionType.CALL, s, k, t, r, q, trueVol));
        ImpliedVol got = iv.impliedVol(OptionType.CALL, price, s, k, t, r, q).orElseThrow();
        assertEquals(trueVol, got.vol(), 1e-5);
        assertTrue(got.converged());
    }

    @Test
    void roundTripRecoversDeepOtmVolViaFallback() {
        // Deep OTM put: vega is tiny, Newton may stall → bisection still recovers σ.
        double s = 100, k = 70, t = 0.25, r = 0.04, q = 0.0, trueVol = 0.45;
        double price = bsm.price(new Inputs(OptionType.PUT, s, k, t, r, q, trueVol));
        ImpliedVol got = iv.impliedVol(OptionType.PUT, price, s, k, t, r, q).orElseThrow();
        assertEquals(trueVol, got.vol(), 1e-4);
    }

    @Test
    void roundTripAcrossSmileOfStrikes() {
        double s = 100, t = 0.5, r = 0.04, q = 0.01, trueVol = 0.30;
        for (double k = 80; k <= 120; k += 5) {
            double price = bsm.price(new Inputs(OptionType.CALL, s, k, t, r, q, trueVol));
            ImpliedVol got = iv.impliedVol(OptionType.CALL, price, s, k, t, r, q).orElseThrow();
            assertEquals(trueVol, got.vol(), 1e-4, "strike " + k);
        }
    }

    @Test
    void priceBelowIntrinsicHasNoImpliedVol() {
        // A call priced under its discounted intrinsic value is an arbitrage — no σ.
        double s = 120, k = 100, t = 0.5, r = 0.05, q = 0.0;
        double belowIntrinsic = 0.01;
        Optional<ImpliedVol> got = iv.impliedVol(OptionType.CALL, belowIntrinsic, s, k, t, r, q);
        assertTrue(got.isEmpty());
    }

    @Test
    void zeroOrNegativeInputsReturnEmpty() {
        assertTrue(iv.impliedVol(OptionType.CALL, 0.0, 100, 100, 0.5, 0.04, 0).isEmpty());
        assertTrue(iv.impliedVol(OptionType.CALL, 5.0, 100, 100, 0.0, 0.04, 0).isEmpty());
    }
}
