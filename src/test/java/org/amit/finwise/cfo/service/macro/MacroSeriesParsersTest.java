package org.amit.finwise.cfo.service.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser tests for the DF-3 macro sources: FBIL par-yield curve + USD/INR
 * reference rate, the RBI current-rates panel (repo/SDF/MSF/CRR), and MOSPI
 * data.gov.in monthly records. Each parser is tolerant of layout noise but only
 * emits values it can actually read.
 */
class MacroSeriesParsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ── FBIL par yields ──────────────────────────────────────────────────────

    @Test
    void fbilParYields_mapsTargetTenorsAndIgnoresOthers() {
        String csv = """
                Maturity,Par Yield (%)
                91 Days,6.10
                1 Year,6.35
                2 Year,6.55
                5 Year,6.85
                10 Year,7.20
                30 Year,7.45
                """;
        Map<MacroSeriesCode, BigDecimal> tenors = MacroSeriesParsers.parseFbilParYields(csv);

        assertEquals(new BigDecimal("6.10"), tenors.get(MacroSeriesCode.GSEC_3M), "91 days ≈ 3M");
        assertEquals(new BigDecimal("6.35"), tenors.get(MacroSeriesCode.GSEC_1Y));
        assertEquals(new BigDecimal("6.85"), tenors.get(MacroSeriesCode.GSEC_5Y));
        assertEquals(new BigDecimal("7.20"), tenors.get(MacroSeriesCode.GSEC_10Y));
        assertEquals(4, tenors.size(), "2Y and 30Y are not curve signals and must be dropped");
    }

    @Test
    void fbilParYields_emptyWhenNoRecognisedTenors() {
        assertTrue(MacroSeriesParsers.parseFbilParYields("garbage\nno,tenors,here").isEmpty());
    }

    // ── FBIL USD/INR reference rate ──────────────────────────────────────────

    @Test
    void usdInrReferenceRate_extractsRateAfterMarker() {
        assertEquals(Optional.of(new BigDecimal("85.1234")),
                MacroSeriesParsers.parseUsdInrReferenceRate("USD/INR Reference Rate,85.1234"));
    }

    @Test
    void usdInrReferenceRate_emptyWhenMarkerAbsent() {
        assertTrue(MacroSeriesParsers.parseUsdInrReferenceRate("EUR/INR,92.10").isEmpty());
    }

    // ── RBI current rates ────────────────────────────────────────────────────

    @Test
    void rbiRates_readsAllFourPolicyRates() {
        String html = """
                <ul class="current-rates">
                  <li>Policy Repo Rate : 6.50%</li>
                  <li>Standing Deposit Facility (SDF) Rate : 6.25%</li>
                  <li>Marginal Standing Facility (MSF) Rate : 6.75%</li>
                  <li>Cash Reserve Ratio : 4.00%</li>
                </ul>
                """;
        MacroSeriesParsers.RbiRates rates = MacroSeriesParsers.parseRbiRates(html);

        assertEquals(new BigDecimal("6.50"), rates.repo());
        assertEquals(new BigDecimal("6.25"), rates.sdf());
        assertEquals(new BigDecimal("6.75"), rates.msf());
        assertEquals(new BigDecimal("4.00"), rates.crr());
    }

    @Test
    void rbiRates_emptyOnPageRedesign() {
        assertTrue(MacroSeriesParsers.parseRbiRates("<html>no rates panel</html>").isEmpty());
    }

    // ── MOSPI data.gov.in ────────────────────────────────────────────────────

    @Test
    void mospiRecords_parseMonthNameAndNumberToMonthEnd() {
        String json = """
                {"records":[
                  {"month":"April","year":"2026","value":"4.83"},
                  {"month":"03","year":"2026","value":"5.10"},
                  {"month":"bad","year":"2026","value":"x"}
                ]}
                """;
        List<MacroSeriesParsers.MospiObs> obs =
                MacroSeriesParsers.parseDataGovRecords(json, "month", "year", "value", mapper);

        assertEquals(2, obs.size(), "the malformed row is skipped, not fatal");
        assertEquals(LocalDate.of(2026, 4, 30), obs.get(0).obsDate());
        assertEquals(new BigDecimal("4.83"), obs.get(0).value());
        assertEquals(LocalDate.of(2026, 3, 31), obs.get(1).obsDate());
    }

    @Test
    void mospiRecords_emptyWhenRecordsKeyMissing() {
        assertTrue(MacroSeriesParsers.parseDataGovRecords(
                "{\"status\":\"error\"}", "month", "year", "value", mapper).isEmpty());
    }
}
