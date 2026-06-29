package org.amit.finwise.cfo.service.macro;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class FiiFlowFactorServiceTest {

    private final FiiFlowFactorService service = new FiiFlowFactorService();

    @Test
    void orthogonalizedFiiIsUncorrelatedWithMarket() {
        Random rng = new Random(7L);
        int T = 100;
        double[] mkt = new double[T];
        double[] fiiRaw = new double[T];
        for (int i = 0; i < T; i++) {
            mkt[i] = rng.nextGaussian() * 0.01;
            fiiRaw[i] = mkt[i] * 0.5 + rng.nextGaussian() * 0.02;
        }

        double[] fiiZ = service.zScore20d(fiiRaw);
        double[] residual = service.orthogonalize(fiiZ, mkt);

        assertThat(Math.abs(pearsonCorr(residual, mkt))).isLessThan(0.05);
    }

    @Test
    void zScore20dOutputHasSameLengthAsInput() {
        double[] raw = new double[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        double[] out = service.zScore20d(raw);
        assertThat(out).hasSize(raw.length);
    }

    private static double pearsonCorr(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double meanA = 0, meanB = 0;
        for (int i = 0; i < n; i++) { meanA += a[i]; meanB += b[i]; }
        meanA /= n;
        meanB /= n;
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < n; i++) {
            num += (a[i] - meanA) * (b[i] - meanB);
            da += (a[i] - meanA) * (a[i] - meanA);
            db += (b[i] - meanB) * (b[i] - meanB);
        }
        double denom = Math.sqrt(da * db);
        return denom < 1e-12 ? 0 : num / denom;
    }
}
