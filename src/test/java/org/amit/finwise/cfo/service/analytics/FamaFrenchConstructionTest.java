package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.service.analytics.FactorReturnService.FamaFrench;
import org.amit.finwise.cfo.service.analytics.FactorReturnService.Rebalance;
import org.amit.finwise.cfo.service.analytics.FactorReturnService.SmbHml;
import org.amit.finwise.cfo.service.analytics.FactorReturnService.StockObs;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Construction tests for the Fama-French (1993) 2×3 SMB/HML factors (BPR-6).
 *
 * A synthetic universe with planted size and value premia must recover positive
 * SMB and HML; a universe with no premium must produce ≈0 factors.
 */
class FamaFrenchConstructionTest {

    private final FactorReturnService svc =
            new FactorReturnService(null, new FactorProperties());

    /**
     * Build a 40-name cross-section. Small caps earn {@code sizePremium} extra return,
     * high-B/M names earn {@code valuePremium} extra — both on top of a common base.
     */
    private List<StockObs> universe(double base, double sizePremium, double valuePremium, long seed) {
        Random rnd = new Random(seed);
        List<StockObs> u = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Market cap spans two orders of magnitude; B/M from 0.3 to 2.0.
            double mcap = 1e8 * Math.pow(10, rnd.nextDouble() * 2);   // 1e8 .. 1e10
            double bm = 0.3 + rnd.nextDouble() * 1.7;
            boolean small = mcap < 1e9;       // rough split point
            boolean value = bm > 1.0;
            double ret = base
                    + (small ? sizePremium : 0)
                    + (value ? valuePremium : 0)
                    + rnd.nextGaussian() * 0.001; // small idiosyncratic noise
            u.add(new StockObs("S" + i, mcap, bm, ret));
        }
        return u;
    }

    @Test
    void recoversPositiveSmbFromPlantedSizePremium() {
        SmbHml r = FactorReturnService.computeSmbHml(
                universe(0.01, 0.02, 0.0, 1L)).orElseThrow();
        assertTrue(r.smb() > 0, "small-cap premium must yield positive SMB, got " + r.smb());
    }

    @Test
    void recoversPositiveHmlFromPlantedValuePremium() {
        SmbHml r = FactorReturnService.computeSmbHml(
                universe(0.01, 0.0, 0.02, 2L)).orElseThrow();
        assertTrue(r.hml() > 0, "value premium must yield positive HML, got " + r.hml());
    }

    @Test
    void noPremiumYieldsNegligibleFactors() {
        SmbHml r = FactorReturnService.computeSmbHml(
                universe(0.01, 0.0, 0.0, 3L)).orElseThrow();
        assertTrue(Math.abs(r.smb()) < 0.005, "flat universe → SMB≈0, got " + r.smb());
        assertTrue(Math.abs(r.hml()) < 0.005, "flat universe → HML≈0, got " + r.hml());
    }

    @Test
    void thinUniverseReturnsEmpty() {
        List<StockObs> few = List.of(
                new StockObs("A", 1e9, 1.0, 0.01),
                new StockObs("B", 2e9, 0.5, 0.02));
        assertTrue(FactorReturnService.computeSmbHml(few).isEmpty());
    }

    @Test
    void nonPositiveBookEquityIsExcluded() {
        // 40 valid names + a few with B/M ≤ 0 (negative book equity) that must be dropped.
        List<StockObs> u = new ArrayList<>(universe(0.01, 0.0, 0.0, 4L));
        u.add(new StockObs("NEG1", 5e8, -0.4, 0.5)); // would distort if not excluded
        u.add(new StockObs("NEG2", 5e8, 0.0, -0.5));
        SmbHml r = FactorReturnService.computeSmbHml(u).orElseThrow();
        assertEquals(40, r.n(), "negative-book-equity names must be excluded from the sort");
    }

    @Test
    void buildFamaFrenchRequiresMinimumPeriods() {
        // Fewer than FF_MIN_PERIODS valid periods → empty (caller falls back to index-spread).
        List<Rebalance> few = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 5; i++) {
            few.add(new Rebalance(d.plusMonths(i), universe(0.01, 0.02, 0.0, 10L + i)));
        }
        assertTrue(svc.buildFamaFrench(few).isEmpty());

        List<Rebalance> enough = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            enough.add(new Rebalance(d.plusDays(i), universe(0.01, 0.02, 0.0, 100L + i)));
        }
        Optional<FamaFrench> ff = svc.buildFamaFrench(enough);
        assertTrue(ff.isPresent());
        assertEquals(15, ff.get().smb().size());
        assertTrue(ff.get().note().startsWith("FAMA_FRENCH_LOOKBACK"));
    }
}
