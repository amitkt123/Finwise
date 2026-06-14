package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.VolForecast;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recovery and fallback tests for the GARCH(1,1) maximum-likelihood estimator.
 *
 * The recovery test simulates a long GARCH path with known parameters and checks the
 * fitted α, β land in a tolerance band — Nelder-Mead on a 3000-point Gaussian likelihood
 * is consistent but not exact, so the bands are generous.
 */
class GarchServiceTest {

    private final GarchService service = new GarchService(new RiskProperties());

    @Test
    void recoversSimulatedGarchParameters() {
        double omega = 2e-6, alpha = 0.08, beta = 0.90;
        int T = 3000;
        Random rnd = new Random(20260612L);

        double[] returns = new double[T];
        double sigma2 = omega / (1 - alpha - beta);   // unconditional variance seed
        for (int t = 0; t < T; t++) {
            double eps = Math.sqrt(sigma2) * rnd.nextGaussian();
            returns[t] = eps;                         // mean-zero by construction
            sigma2 = omega + alpha * eps * eps + beta * sigma2;
        }

        VolForecast vf = service.fit(returns, 0.94);

        assertEquals(VolForecast.Method.GARCH, vf.method(), "should produce a GARCH fit on 3000 obs");
        assertNotNull(vf.alpha());
        assertNotNull(vf.beta());
        assertEquals(alpha, vf.alpha(), 0.04, "alpha recovered within tolerance");
        assertEquals(beta, vf.beta(), 0.05, "beta recovered within tolerance");
        assertTrue(vf.persistence() < 1.0, "stationary fit");
        assertTrue(vf.conditionalDailyVol() > 0);
        assertTrue(vf.longRunDailyVol() > 0);
        assertTrue(vf.tenDayVol() > vf.conditionalDailyVol(), "10-day vol exceeds 1-day vol");
        assertNotNull(vf.logLikelihood());
    }

    @Test
    void selectsGjrAndRecoversPositiveLeverageOnAsymmetricSeries() {
        // Simulate GJR-GARCH with a strong leverage effect: negative shocks raise
        // next-period variance by (α+γ) vs α for positive shocks.
        double omega = 2e-6, alpha = 0.02, gamma = 0.15, beta = 0.88;
        int T = 4000;
        Random rnd = new Random(20260614L);

        double[] returns = new double[T];
        double sigma2 = omega / (1 - alpha - 0.5 * gamma - beta);
        for (int t = 0; t < T; t++) {
            double eps = Math.sqrt(sigma2) * rnd.nextGaussian();
            returns[t] = eps;
            double lev = eps < 0 ? 1.0 : 0.0;
            sigma2 = omega + (alpha + gamma * lev) * eps * eps + beta * sigma2;
        }

        VolForecast vf = service.fit(returns, 0.94);

        assertEquals(VolForecast.Method.GJR_GARCH, vf.method(),
                "asymmetric model should win on AIC for a leverage series");
        assertTrue(vf.isAsymmetric());
        assertNotNull(vf.leverageGamma());
        assertTrue(vf.leverageGamma() > 0.05, "recovered leverage γ̂ must be clearly positive, got "
                + vf.leverageGamma());
        // Stationarity α + γ/2 + β < 1 enforced by the reparameterization.
        assertTrue(vf.persistence() < 1.0);
        assertTrue(vf.notes().stream().anyMatch(n -> n.contains("leverage coefficient")));
    }

    @Test
    void symmetricDataPrefersGarchWithNegligibleLeverage() {
        // Plain GARCH(1,1) data → selection should keep the 3-param symmetric model.
        double omega = 2e-6, alpha = 0.07, beta = 0.90;
        int T = 4000;
        Random rnd = new Random(7L);
        double[] returns = new double[T];
        double sigma2 = omega / (1 - alpha - beta);
        for (int t = 0; t < T; t++) {
            double eps = Math.sqrt(sigma2) * rnd.nextGaussian();
            returns[t] = eps;
            sigma2 = omega + alpha * eps * eps + beta * sigma2;
        }

        VolForecast vf = service.fit(returns, 0.94);
        assertEquals(VolForecast.Method.GARCH, vf.method(),
                "symmetric data should not select the extra leverage parameter");
        assertNull(vf.leverageGamma());
    }

    @Test
    void fallsBackToEwmaWhenTooFewObservations() {
        Random rnd = new Random(1L);
        double[] returns = new double[100];           // < MIN_GARCH_OBSERVATIONS
        for (int i = 0; i < returns.length; i++) returns[i] = 0.01 * rnd.nextGaussian();

        VolForecast vf = service.fit(returns, 0.94);

        assertEquals(VolForecast.Method.EWMA, vf.method());
        assertNull(vf.alpha());
        assertNull(vf.omega());
        assertEquals(0.94, vf.persistence(), 1e-12, "EWMA persistence == lambda");
        assertTrue(Double.isNaN(vf.longRunDailyVol()), "EWMA has no long-run level");
        assertFalse(vf.notes().isEmpty());
        assertTrue(vf.notes().getFirst().startsWith("GARCH_FALLBACK"));
        // flat EWMA: 10-day vol == √10 × 1-day vol
        assertEquals(Math.sqrt(10) * vf.conditionalDailyVol(), vf.tenDayVol(), 1e-12);
    }
}
