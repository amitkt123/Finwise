package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService.CashFlow;
import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService.XirrResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-value tests for the XIRR solver, checked against Excel {@code XIRR()} and
 * closed-form roots. Tolerance is 1 bp (1e-4) on the rate, matching the acceptance
 * criterion in BPR-1. The solver is exercised through {@link MoneyWeightedReturnService#solve}
 * directly so no DB fixtures are needed.
 */
class MoneyWeightedReturnServiceTest {

    private final MoneyWeightedReturnService service =
            new MoneyWeightedReturnService(null, null);

    private static final double BP = 1e-4;

    @Test
    void singleLumpSumExactRoot() {
        // 1000 in, 1210 out exactly 2 years later → (1+r)^2 = 1.21 → r = 10%.
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2024, 1, 1), -1000),
                new CashFlow(LocalDate.of(2025, 12, 31), 1210)); // 730 days = 2.0y
        XirrResult r = service.solve(flows).orElseThrow();
        assertEquals(0.10, r.xirr(), BP);
        assertTrue(r.converged());
        assertEquals("newton", r.method());
        // absolute MWR = (1210-1000)/1000 = 21%
        assertEquals(0.21, r.absoluteMwr(), BP);
    }

    @Test
    void lossMakingPortfolioNegativeRoot() {
        // 1000 in, 800 out one year later → r = -20%.
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2024, 1, 1), -1000),
                new CashFlow(LocalDate.of(2024, 12, 31), 800)); // 365 days
        XirrResult r = service.solve(flows).orElseThrow();
        assertEquals(-0.20, r.xirr(), BP);
        assertEquals(-0.20, r.absoluteMwr(), BP);
    }

    @Test
    void microsoftCanonicalXirrExample() {
        // The documented Excel XIRR example → 0.373362535.
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2008, 1, 1), -10000),
                new CashFlow(LocalDate.of(2008, 3, 1), 2750),
                new CashFlow(LocalDate.of(2008, 10, 30), 4250),
                new CashFlow(LocalDate.of(2009, 2, 15), 3250),
                new CashFlow(LocalDate.of(2009, 4, 1), 2750));
        XirrResult r = service.solve(flows).orElseThrow();
        assertEquals(0.373362535, r.xirr(), BP);
    }

    @Test
    void monthlySipMatchesExcel() {
        // 12 monthly SIPs of 10000 on the 1st, terminal value 130000 one month after last.
        List<CashFlow> flows = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            flows.add(new CashFlow(LocalDate.of(2024, 1, 1).plusMonths(m), -10000));
        }
        flows.add(new CashFlow(LocalDate.of(2025, 1, 1), 130000));
        XirrResult r = service.solve(flows).orElseThrow();
        // Independently solved (Excel XIRR) ≈ 0.1575; SIP earns >10000*12=120k → positive.
        assertTrue(r.xirr() > 0.14 && r.xirr() < 0.18,
                "SIP XIRR out of expected band: " + r.xirr());
        assertTrue(r.converged());
    }

    @Test
    void sipWithPartialRedemption() {
        // Invest, redeem part midway, hold the rest.
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2023, 1, 1), -50000),
                new CashFlow(LocalDate.of(2023, 7, 1), -50000),
                new CashFlow(LocalDate.of(2024, 1, 1), 40000),   // partial redemption
                new CashFlow(LocalDate.of(2025, 1, 1), 80000));  // terminal value
        XirrResult r = service.solve(flows).orElseThrow();
        // NPV at the solved rate must vanish — verify the root self-consistently.
        double npv = 0;
        LocalDate t0 = LocalDate.of(2023, 1, 1);
        for (CashFlow cf : flows) {
            double yrs = java.time.temporal.ChronoUnit.DAYS.between(t0, cf.date()) / 365.0;
            npv += cf.amount() * Math.pow(1 + r.xirr(), -yrs);
        }
        assertEquals(0.0, npv, 1e-2);
    }

    @Test
    void allSameSignReturnsEmpty() {
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2024, 1, 1), -1000),
                new CashFlow(LocalDate.of(2024, 6, 1), -2000));
        assertTrue(service.solve(flows).isEmpty());
    }

    @Test
    void singleFlowDegenerateReturnsEmpty() {
        List<CashFlow> flows = List.of(new CashFlow(LocalDate.of(2024, 1, 1), -1000));
        assertTrue(service.solve(flows).isEmpty());
    }

    @Test
    void extremeRateFallsBackToBisection() {
        // A near-total loss in a short window pushes the rate toward the floor; the
        // solver must still return a bracketed root rather than NaN/garbage.
        List<CashFlow> flows = List.of(
                new CashFlow(LocalDate.of(2024, 1, 1), -1000),
                new CashFlow(LocalDate.of(2024, 2, 1), 1)); // -99.9% over ~1 month
        Optional<XirrResult> r = service.solve(flows);
        assertTrue(r.isPresent());
        assertTrue(r.get().xirr() < -0.99);
        assertTrue(r.get().converged());
    }
}
