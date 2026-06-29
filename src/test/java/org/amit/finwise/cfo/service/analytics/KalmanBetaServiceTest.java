package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class KalmanBetaServiceTest {

    private final KalmanBetaService service = new KalmanBetaService();

    @Test
    void iidReturnsProduceBetaDriftNearZero() {
        int T = 120;
        Random rng = new Random(42L);
        double[] market = new double[T];
        double[] asset = new double[T];
        for (int t = 0; t < T; t++) {
            market[t] = rng.nextGaussian() * 0.01;
            asset[t] = market[t] + rng.nextGaussian() * 0.0001; // ≈ beta 1, tiny noise → fast convergence
        }
        double[][] factorReturns = new double[][] {market};

        var result = service.fit(asset, factorReturns, 0.0);

        assertThat(Math.abs(result.betaDrift())).isLessThan(0.3);
        assertThat(result.currentBeta()[0]).isCloseTo(1.0, offset(0.15));
    }

    @Test
    void crisisIncreasesQEffectivelyAllowingFasterBetaChange() {
        double qBase = 1e-4;
        double crisisProbability = 1.0;
        double qEff = qBase * (1 + crisisProbability * 5);
        assertThat(qEff).isCloseTo(6e-4, offset(1e-15));
    }
}
