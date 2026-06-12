package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Known-answer tests for the Cornish-Fisher quantile expansion used in
 * fat-tail-adjusted VaR.
 */
class CornishFisherTest {

    @Test
    void reducesToGaussianForNormalReturns() {
        assertEquals(-1.645, PortfolioRiskService.cornishFisherZ(-1.645, 0.0, 0.0), 1e-12,
                "zero skew and zero excess kurtosis must leave the quantile unchanged");
    }

    @Test
    void negativeSkewAndFatTailsWidenTheLossQuantile() {
        // Typical Indian mid-cap profile: skew −0.5, excess kurtosis 4
        double zCf = PortfolioRiskService.cornishFisherZ(-2.326, -0.5, 4.0);
        assertTrue(zCf < -2.326,
                "negative skew + fat tails must push the 99% loss quantile further out, got " + zCf);
    }

    @Test
    void matchesHandComputedExpansion() {
        // z = -1.645, γ₁ = -0.5, γ₂ = 3:
        // term1 = (z²−1)/6·γ₁   = (2.706025−1)/6 × −0.5  = −0.142168...
        // term2 = (z³−3z)/24·γ₂ = (−4.451411+4.935)/24×3 =  0.060448...
        // term3 = −(2z³−5z)/36·γ₁² = −(−8.902822+8.225)/36×0.25 = 0.004707...
        double expected = -1.645
                + (1.645 * 1.645 - 1) / 6.0 * (-0.5)
                + (-Math.pow(1.645, 3) + 3 * 1.645) / 24.0 * 3.0
                - (2 * -Math.pow(1.645, 3) + 5 * 1.645) / 36.0 * 0.25;
        assertEquals(expected, PortfolioRiskService.cornishFisherZ(-1.645, -0.5, 3.0), 1e-12);
    }

    @Test
    void passesThroughWhenMomentsUnavailable() {
        assertEquals(-1.645, PortfolioRiskService.cornishFisherZ(-1.645, Double.NaN, 2.0), 1e-12);
    }

    @Test
    void validityDomain_acceptsModerateMomentsRejectsExtremes() {
        assertTrue(PortfolioRiskService.cornishFisherValid(-0.5, 4.0),
                "typical Indian mid-cap moments are inside the domain");
        assertTrue(PortfolioRiskService.cornishFisherValid(-2.5, 10.0),
                "boundary values are still accepted");
        assertFalse(PortfolioRiskService.cornishFisherValid(-4.0, 15.0),
                "small-cap stress moments are outside the domain");
        assertFalse(PortfolioRiskService.cornishFisherValid(3.0, 2.0),
                "extreme positive skew alone invalidates the expansion");
        assertFalse(PortfolioRiskService.cornishFisherValid(0.0, 12.0),
                "extreme kurtosis alone invalidates the expansion");
    }

    @Test
    void validityDomain_treatsNaNMomentsAsValidPassThrough() {
        // cornishFisherZ already degrades to the plain Gaussian quantile on NaN,
        // so the guard must not also force the parametric substitution.
        assertTrue(PortfolioRiskService.cornishFisherValid(Double.NaN, Double.NaN));
    }
}
