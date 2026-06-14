package org.amit.finwise.cfo.service.analytics.options;

import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Inputs;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.EodOptionRow;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.ExpirySmile;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.TermPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chain service round-trips a synthetic flat-vol chain: every settlement price
 * is generated at a known σ, so the recovered smile must be flat at that σ and the
 * ATM term structure must report it at each expiry.
 */
class OptionChainServiceTest {

    private final BlackScholesService bsm = new BlackScholesService();
    private final OptionChainService chain =
            new OptionChainService(new ImpliedVolatilityService(bsm));

    private static final LocalDate ASOF = LocalDate.of(2026, 6, 14);
    private static final double SPOT = 1000, RATE = 0.06, Q = 0.0, TRUE_VOL = 0.24;

    private EodOptionRow row(double strike, LocalDate expiry, OptionType type) {
        double t = ChronoUnit.DAYS.between(ASOF, expiry) / 365.0;
        double px = bsm.price(new Inputs(type, SPOT, strike, t, RATE, Q, TRUE_VOL));
        return new EodOptionRow("NIFTY", type, strike, expiry, px);
    }

    @Test
    void recoversFlatSmileAndAtmTermStructure() {
        LocalDate near = ASOF.plusDays(30);
        LocalDate far = ASOF.plusDays(90);
        List<EodOptionRow> rows = new ArrayList<>();
        for (double k = 900; k <= 1100; k += 50) {
            rows.add(row(k, near, OptionType.CALL));
            rows.add(row(k, far, OptionType.CALL));
        }

        List<ExpirySmile> smiles = chain.buildSmiles(rows, SPOT, RATE, Q, ASOF);
        assertEquals(2, smiles.size());
        for (ExpirySmile s : smiles) {
            s.points().forEach(p ->
                    assertEquals(TRUE_VOL, p.impliedVol(), 1e-3, "strike " + p.strike()));
            assertEquals(1000.0, s.atmStrike(), 1e-9, "ATM strike is nearest to spot");
            assertEquals(TRUE_VOL, s.atmImpliedVol(), 1e-3);
        }

        List<TermPoint> term = chain.atmTermStructure(rows, SPOT, RATE, Q, ASOF);
        assertEquals(2, term.size());
        assertTrue(term.get(0).tYears() < term.get(1).tYears(), "ascending by maturity");
        term.forEach(tp -> assertEquals(TRUE_VOL, tp.atmImpliedVol(), 1e-3));
    }

    @Test
    void expiredContractsAreDropped() {
        List<EodOptionRow> rows = List.of(
                new EodOptionRow("NIFTY", OptionType.CALL, 1000, ASOF.minusDays(1), 5.0),
                row(1000, ASOF.plusDays(30), OptionType.CALL));
        assertEquals(1, chain.buildSmiles(rows, SPOT, RATE, Q, ASOF).size());
    }
}
