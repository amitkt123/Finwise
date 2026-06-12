package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.service.analytics.AttributionService.PeriodAttribution;
import org.amit.finwise.cfo.service.analytics.AttributionService.SectorPeriod;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden tests for Brinson-Fachler attribution (Phase 12b).
 *
 * The 3-sector textbook case reconciles EXACTLY: Σ_s (A_s + S_s + I_s) = r_p − r_b.
 * The multi-period case verifies Cariño geometric linking sums to the compounded
 * excess return with a vanishing residual.
 */
class AttributionServiceTest {

    private static final double EPS = 1e-12;

    @Test
    void textbookThreeSectorReconcilesExactly() {
        // Classic Brinson-Fachler worked example.
        //                 w_p   w_b   r_p     r_b
        //   Sector A      0.5   0.4   0.10    0.08
        //   Sector B      0.3   0.4   0.05    0.04
        //   Sector C      0.2   0.2  -0.02   -0.01
        Map<String, SectorPeriod> bucket = new LinkedHashMap<>();
        bucket.put("A", new SectorPeriod(0.5, 0.4, 0.10, 0.08));
        bucket.put("B", new SectorPeriod(0.3, 0.4, 0.05, 0.04));
        bucket.put("C", new SectorPeriod(0.2, 0.2, -0.02, -0.01));

        PeriodAttribution a = AttributionService.attributeSinglePeriod(bucket);

        assertEquals(0.061, a.rP(), EPS, "portfolio return");
        assertEquals(0.046, a.rB(), EPS, "benchmark return");
        assertEquals(0.015, a.excess(), EPS, "excess return");

        // Per-sector effects against hand-computed values.
        assertArrayEquals(new double[]{0.0034, 0.008, 0.002}, a.effects().get("A"), EPS);
        assertArrayEquals(new double[]{0.0006, 0.004, -0.001}, a.effects().get("B"), EPS);
        assertArrayEquals(new double[]{0.0, -0.002, 0.0}, a.effects().get("C"), EPS);

        // Effects reconcile to the excess return exactly.
        double alloc = 0, sel = 0, inter = 0;
        for (double[] e : a.effects().values()) { alloc += e[0]; sel += e[1]; inter += e[2]; }
        assertEquals(0.004, alloc, EPS, "total allocation");
        assertEquals(0.010, sel, EPS, "total selection");
        assertEquals(0.001, inter, EPS, "total interaction");
        assertEquals(a.excess(), alloc + sel + inter, EPS, "Σ effects = r_p − r_b");
    }

    @Test
    void unnormalizedWeightsAreRenormalizedBeforeAttribution() {
        // Same shape as above but weights given as percentages (sum to 100), not fractions.
        Map<String, SectorPeriod> pct = new LinkedHashMap<>();
        pct.put("A", new SectorPeriod(50, 40, 0.10, 0.08));
        pct.put("B", new SectorPeriod(30, 40, 0.05, 0.04));
        pct.put("C", new SectorPeriod(20, 20, -0.02, -0.01));

        PeriodAttribution a = AttributionService.attributeSinglePeriod(pct);
        assertEquals(0.015, a.excess(), EPS);
        assertArrayEquals(new double[]{0.0034, 0.008, 0.002}, a.effects().get("A"), EPS);
    }

    @Test
    void carinoLinkingSumsToCompoundedExcessWithVanishingResidual() {
        // Single sector → allocation/interaction vanish; selection per period = r_p − r_b.
        // Linked selection must equal the GEOMETRIC excess, not the arithmetic sum.
        PeriodAttribution p1 = AttributionService.attributeSinglePeriod(
                Map.of("ALL", new SectorPeriod(1.0, 1.0, 0.10, 0.05)));
        PeriodAttribution p2 = AttributionService.attributeSinglePeriod(
                Map.of("ALL", new SectorPeriod(1.0, 1.0, -0.04, 0.02)));

        AttributionService.LinkedResult linked =
                AttributionService.carinoLink(List.of(p1, p2));

        double rp = 1.10 * 0.96 - 1.0;   // 0.056
        double rb = 1.05 * 1.02 - 1.0;   // 0.071
        assertEquals(rp, linked.Rp(), 1e-12);
        assertEquals(rb, linked.Rb(), 1e-12);

        double geomExcess = rp - rb;     // −0.015
        assertEquals(geomExcess, linked.selection(), 1e-9, "linked selection = geometric excess");
        assertEquals(0.0, linked.allocation(), 1e-12);
        assertEquals(0.0, linked.interaction(), 1e-12);
        assertEquals(0.0, linked.residual(), 1e-9, "Cariño residual vanishes");

        // Arithmetic sum (0.05 + −0.06 = −0.01) must NOT equal the linked total.
        assertNotEquals(-0.01, linked.selection(), 1e-4);
    }

    @Test
    void compoundByMonthMultipliesWithinCalendarMonth() {
        NavigableMap<LocalDate, Double> daily = new TreeMap<>();
        daily.put(LocalDate.of(2026, 1, 5), 0.01);
        daily.put(LocalDate.of(2026, 1, 20), 0.02);
        daily.put(LocalDate.of(2026, 2, 10), -0.01);

        Map<YearMonth, Double> monthly = AttributionService.compoundByMonth(daily);
        assertEquals(1.01 * 1.02 - 1.0, monthly.get(YearMonth.of(2026, 1)), 1e-12);
        assertEquals(-0.01, monthly.get(YearMonth.of(2026, 2)), 1e-12);
    }
}
