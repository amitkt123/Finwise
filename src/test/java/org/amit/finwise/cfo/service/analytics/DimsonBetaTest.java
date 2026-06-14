package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.service.analytics.CovarianceEngine.DimsonBeta;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BPR-9: a stale (lagged-adjustment) illiquid series biases naive OLS beta downward;
 * the Dimson lead/lag sum recovers the true beta.
 */
class DimsonBetaTest {

    private final CovarianceEngine engine = new CovarianceEngine();

    @Test
    void dimsonRecoversTrueBetaThatNaiveOlsUnderestimates() {
        Random rnd = new Random(20260614L);
        int n = 400;
        double betaTrue = 1.20;

        LocalDate start = LocalDate.of(2024, 1, 1);
        double[] mkt = new double[n];
        for (int t = 0; t < n; t++) mkt[t] = 0.012 * rnd.nextGaussian();

        NavigableMap<LocalDate, Double> benchmark = new TreeMap<>();
        NavigableMap<LocalDate, Double> stock = new TreeMap<>();
        // Stale price: the stock reacts to the market half today, half tomorrow
        // (a one-day partial-adjustment lag typical of thin trading).
        for (int t = 0; t < n; t++) {
            LocalDate d = start.plusDays(t);
            benchmark.put(d, mkt[t]);
            double prev = t > 0 ? mkt[t - 1] : 0.0;
            double s = betaTrue * (0.5 * mkt[t] + 0.5 * prev) + 0.001 * rnd.nextGaussian();
            stock.put(d, s);
        }

        DimsonBeta db = engine.dimsonBetaStats(stock, benchmark, 1);

        assertTrue(db.isUsable());
        // Naive beta is biased toward ~0.5·βtrue because it ignores the lagged reaction.
        assertTrue(db.naiveBeta() < 0.85,
                "naive beta should under-estimate the lagged exposure, got " + db.naiveBeta());
        // Dimson sum recovers the true beta.
        assertEquals(betaTrue, db.dimsonBeta(), 0.10,
                "Dimson lead/lag sum should recover βtrue, got " + db.dimsonBeta());
        assertTrue(db.dimsonBeta() > db.naiveBeta(), "correction lifts the biased naive beta");
    }

    @Test
    void zeroLeadLagReturnsNaiveBeta() {
        NavigableMap<LocalDate, Double> s = new TreeMap<>();
        NavigableMap<LocalDate, Double> b = new TreeMap<>();
        LocalDate start = LocalDate.of(2024, 1, 1);
        Random rnd = new Random(1L);
        for (int t = 0; t < 100; t++) {
            double m = 0.01 * rnd.nextGaussian();
            b.put(start.plusDays(t), m);
            s.put(start.plusDays(t), 0.9 * m);
        }
        DimsonBeta db = engine.dimsonBetaStats(s, b, 0);
        assertEquals(db.naiveBeta(), db.dimsonBeta(), 1e-12);
    }
}
