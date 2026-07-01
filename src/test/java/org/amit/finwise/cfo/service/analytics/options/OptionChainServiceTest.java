package org.amit.finwise.cfo.service.analytics.options;

import org.amit.finwise.cfo.model.IvTermStructureAlert;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.Inputs;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.EodOptionRow;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.ExpirySmile;
import org.amit.finwise.cfo.service.analytics.options.OptionChainService.TermPoint;
import org.amit.finwise.marketdata.provider.DataEnvelope;
import org.amit.finwise.marketdata.provider.DataQuality;
import org.amit.finwise.marketdata.provider.adapter.NSEOptionChainAdapter;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chain service round-trips a synthetic flat-vol chain: every settlement price
 * is generated at a known σ, so the recovered smile must be flat at that σ and the
 * ATM term structure must report it at each expiry.
 */
class OptionChainServiceTest {

    private final BlackScholesService bsm = new BlackScholesService();
    private final NSEOptionChainAdapter adapter = mock(NSEOptionChainAdapter.class);
    // Legacy EOD smile/term-structure tests never touch the live-chain adapter.
    private final OptionChainService chain =
            new OptionChainService(new ImpliedVolatilityService(bsm), adapter);

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

    // ── Live option chain: IV term structure inversion ─────────────────────────

    private Map<String, Object> nseChain(double underlying, List<Map<String, Object>> rows) {
        Map<String, Object> records = new LinkedHashMap<>();
        records.put("underlyingValue", underlying);
        records.put("data", rows);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("records", records);
        return root;
    }

    private Map<String, Object> optionRow(double strike, String expiry, Double ceIv, Double peIv) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("strikePrice", strike);
        row.put("expiryDate", expiry);
        if (ceIv != null) {
            Map<String, Object> ce = new LinkedHashMap<>();
            ce.put("impliedVolatility", ceIv);
            row.put("CE", ce);
        }
        if (peIv != null) {
            Map<String, Object> pe = new LinkedHashMap<>();
            pe.put("impliedVolatility", peIv);
            row.put("PE", pe);
        }
        return row;
    }

    @Test
    void detectTermStructureInversion_emptyWhenAdapterMissing() {
        when(adapter.fetchOptionChain("NIFTY"))
                .thenReturn(DataEnvelope.missing("nse-option-chain", "API down"));
        assertTrue(chain.detectTermStructureInversion("NIFTY").isEmpty());
    }

    @Test
    void detectTermStructureInversion_firesWhenNearIvExceedsFarIv() {
        Map<String, Object> nearAtm = optionRow(1000, "30-Jan-2026", 35.0, 33.0);   // ATM IV 34
        Map<String, Object> nearOtm = optionRow(1100, "30-Jan-2026", 20.0, 18.0);   // farther from spot
        Map<String, Object> farAtm = optionRow(1000, "27-Feb-2026", 20.0, 20.0);    // ATM IV 20
        Map<String, Object> chainMap = nseChain(1000.0, List.of(nearAtm, nearOtm, farAtm));

        when(adapter.fetchOptionChain("NIFTY"))
                .thenReturn(DataEnvelope.of(chainMap, "nse-option-chain", DataQuality.LIVE));

        Optional<IvTermStructureAlert> alert = chain.detectTermStructureInversion("NIFTY");
        assertTrue(alert.isPresent());
        assertEquals("NIFTY", alert.get().symbol());
        assertEquals(34.0, alert.get().nearTermIvPct(), 1e-9);
        assertEquals(20.0, alert.get().farTermIvPct(), 1e-9);
        assertEquals(14.0, alert.get().inversionMagnitudePct(), 1e-9);
    }

    @Test
    void detectTermStructureInversion_emptyWhenTermStructureNormal() {
        Map<String, Object> nearAtm = optionRow(1000, "30-Jan-2026", 18.0, 18.0);
        Map<String, Object> farAtm = optionRow(1000, "27-Feb-2026", 20.0, 20.0);
        Map<String, Object> chainMap = nseChain(1000.0, List.of(nearAtm, farAtm));

        when(adapter.fetchOptionChain("NIFTY"))
                .thenReturn(DataEnvelope.of(chainMap, "nse-option-chain", DataQuality.LIVE));

        assertTrue(chain.detectTermStructureInversion("NIFTY").isEmpty());
    }

    @Test
    void detectTermStructureInversion_emptyWhenFewerThanTwoExpiries() {
        Map<String, Object> onlyExpiry = optionRow(1000, "30-Jan-2026", 25.0, 25.0);
        Map<String, Object> chainMap = nseChain(1000.0, List.of(onlyExpiry));

        when(adapter.fetchOptionChain("NIFTY"))
                .thenReturn(DataEnvelope.of(chainMap, "nse-option-chain", DataQuality.LIVE));

        assertTrue(chain.detectTermStructureInversion("NIFTY").isEmpty());
    }

    @Test
    void detectTermStructureInversion_ignoresZeroIvSideRatherThanAveragingIt() {
        // CE untraded this session (NSE reports 0, not a real vol) — must not drag the real
        // PE=24.0 down to a fabricated 12.0 average.
        Map<String, Object> nearAtm = optionRow(1000, "30-Jan-2026", 0.0, 24.0);
        Map<String, Object> farAtm = optionRow(1000, "27-Feb-2026", 15.0, 15.0);
        Map<String, Object> chainMap = nseChain(1000.0, List.of(nearAtm, farAtm));

        when(adapter.fetchOptionChain("NIFTY"))
                .thenReturn(DataEnvelope.of(chainMap, "nse-option-chain", DataQuality.LIVE));

        Optional<IvTermStructureAlert> alert = chain.detectTermStructureInversion("NIFTY");
        assertTrue(alert.isPresent());
        assertEquals(24.0, alert.get().nearTermIvPct(), 1e-9,
                "near IV must be the real PE side (24.0), not a 0/24 average (12.0)");
        assertEquals(15.0, alert.get().farTermIvPct(), 1e-9);
    }
}
