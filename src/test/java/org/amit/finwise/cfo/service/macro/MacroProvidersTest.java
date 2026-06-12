package org.amit.finwise.cfo.service.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.cfo.service.price.NseSessionManager;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parser/signal tests for the Phase 6 macro providers: the NSE FII/DII flow
 * parser (against a stored response fixture), the yield-curve shape
 * classification, and the RBI repo-rate regex extraction.
 */
class MacroProvidersTest {

    // ── 6a: FII/DII parser ───────────────────────────────────────────────────

    private static final String FII_DII_FIXTURE = """
            [
              {"category":"DII **","date":"12-Jun-2026","buyValue":"15,000.50",
               "sellValue":"13,000.25","netValue":"2,000.25"},
              {"category":"FII/FPI *","date":"12-Jun-2026","buyValue":"18,500.10",
               "sellValue":"19,734.66","netValue":"-1,234.56"}
            ]
            """;

    private FiiDiiFlowProvider provider() {
        return new FiiDiiFlowProvider(new NseSessionManager(), new ObjectMapper());
    }

    @Test
    void fiiDiiParser_readsCategoriesCommaValuesAndDate() throws Exception {
        FiiDiiFlowProvider.FiiDiiFlow flow = provider().parseResponse(FII_DII_FIXTURE);

        assertEquals(new BigDecimal("-1234.56"), flow.fiiNetFlowCr());
        assertEquals(new BigDecimal("2000.25"), flow.diiNetFlowCr());
        assertEquals(LocalDate.of(2026, 6, 12), flow.asOf());
    }

    @Test
    void fiiDiiParser_failsStrictlyOnMissingCategory() {
        String missingFii = """
                [{"category":"DII **","date":"12-Jun-2026","netValue":"2,000.25"}]
                """;
        assertThrows(Exception.class, () -> provider().parseResponse(missingFii),
                "parse-or-fail: a response without the FII row must not produce partial data");
    }

    @Test
    void fiiDiiParser_failsStrictlyOnShapeChange() {
        assertThrows(Exception.class, () -> provider().parseResponse("{\"data\":[]}"),
                "object instead of array = NSE changed the shape; must fail loudly");
    }

    // ── 6c: yield curve ──────────────────────────────────────────────────────

    @Test
    void yieldCurve_classifiesShapesFromSlope() {
        assertEquals(YieldCurveService.CurveShape.INVERTED,
                curve("7.20", "6.90").shape(), "10Y below 3M");
        assertEquals(YieldCurveService.CurveShape.FLAT,
                curve("6.10", "6.50").shape(), "slope 40bp");
        assertEquals(YieldCurveService.CurveShape.NORMAL,
                curve("6.10", "7.20").shape(), "slope 110bp");
        assertEquals(YieldCurveService.CurveShape.STEEP,
                curve("5.50", "7.20").shape(), "slope 170bp");
        assertEquals(new BigDecimal("1.10"), curve("6.10", "7.20").slope10y3m());
    }

    @Test
    void yieldCurve_missingTenorYieldsUnknownNotFabricatedZero() {
        YieldCurveService noShortEnd = new YieldCurveService(null, null, null, new BigDecimal("7.20"));
        assertNull(noShortEnd.slope10y3m(), "no 3M tenor → no slope, never 10Y − 0");
        assertEquals(YieldCurveService.CurveShape.UNKNOWN, noShortEnd.shape());
    }

    private YieldCurveService curve(String y3m, String y10) {
        return new YieldCurveService(new BigDecimal(y3m), null, null, new BigDecimal(y10));
    }

    // ── 6b: RBI repo-rate regex ──────────────────────────────────────────────

    @Test
    void rbiExtractor_readsRateFromCurrentRatesPanel() {
        RbiRateProvider rbi = new RbiRateProvider(true);
        Optional<BigDecimal> rate = rbi.extractRepoRate(
                "<div class=\"rates\"><span>Policy Repo Rate : 6.50%</span></div>");
        assertTrue(rate.isPresent());
        assertEquals(new BigDecimal("6.50"), rate.get());
    }

    @Test
    void rbiExtractor_discardsOutOfBandAndUnmatchedReadings() {
        RbiRateProvider rbi = new RbiRateProvider(true);
        assertTrue(rbi.extractRepoRate("Policy Repo Rate : 45.00%").isEmpty(),
                "45% fails the 3–10% sanity band");
        assertTrue(rbi.extractRepoRate("<html>page redesign, no rates panel</html>").isEmpty());
    }

    @Test
    void rbiProvider_disabledNeverFetches() {
        RbiRateProvider rbi = new RbiRateProvider(false);
        assertTrue(rbi.fetchLiveRepoRate().isEmpty(), "disabled provider must short-circuit");
    }
}
