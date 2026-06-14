package org.amit.finwise.cfo.service;

import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.model.InsightClaim;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the DF-7 claim parser — the pure, I/O-free half of the
 * evaluation loop. Repos are unused by {@code parseClaims}, so the service is
 * constructed with nulls.
 */
class InsightEvaluationServiceTest {

    private final InsightEvaluationService svc =
            new InsightEvaluationService(null, null, null, null, null);

    private AiInsight insight(String content) {
        return AiInsight.builder()
                .id(7L).userId("u1").insightDate(LocalDate.of(2026, 6, 13))
                .insightType(AiInsight.InsightType.DAILY_BRIEF)
                .title("t").content(content).modelUsed("claude")
                .build();
    }

    private Map<String, InsightClaim> byKey(List<InsightClaim> claims) {
        return claims.stream().collect(Collectors.toMap(
                c -> c.getSymbol() + "|" + c.getHorizon(), c -> c));
    }

    @Test
    void extractsDirectionHorizonConfidencePerHolding() {
        String brief = """
                ## Action Items by Time Horizon
                - ⏳ Short-Term (0–7 days):
                  - Accumulate TCS on the dip. Confidence: 0.7
                  - Reduce RELIANCE into strength. Confidence: 0.6
                - 📅 Medium-Term (1–3 months):
                  - Add INFY on sector tailwinds. Confidence: 0.8
                """;
        List<InsightClaim> claims = svc.parseClaims(insight(brief), Set.of("TCS", "RELIANCE", "INFY"));
        Map<String, InsightClaim> m = byKey(claims);

        assertEquals(3, claims.size());
        assertEquals(InsightClaim.Direction.BULLISH, m.get("TCS|H5D").getDirection());
        assertEquals(0.7, m.get("TCS|H5D").getConfidence(), 1e-9);
        assertEquals(InsightClaim.Direction.BEARISH, m.get("RELIANCE|H5D").getDirection());
        assertEquals(EventOutcome.Horizon.H20D, m.get("INFY|H20D").getHorizon());
        // provider + prompt-version stamped for the scoreboard
        assertEquals("claude", m.get("TCS|H5D").getProvider());
        assertEquals(InsightEvaluationService.PROMPT_VERSION, m.get("TCS|H5D").getPromptVersion());
    }

    @Test
    void skipsNonDirectionalAndUnmentionedLines() {
        String brief = """
                - ⏳ Short-Term (0–7 days):
                  - Hold HDFCBANK and watch the RBI decision. Confidence: 0.9
                  - General market commentary with no holding. Confidence: 0.5
                  - Buy WIPRO. (no confidence score here)
                """;
        List<InsightClaim> claims = svc.parseClaims(insight(brief), Set.of("HDFCBANK", "WIPRO"));
        // "Hold/watch" is ambiguous → skipped; the no-confidence Buy line → skipped.
        assertTrue(claims.isEmpty(), "expected no actionable claims, got " + claims);
    }

    @Test
    void dedupesKeepingHighestConfidence() {
        String brief = """
                - ⏳ Short-Term (0–7 days):
                  - Buy TCS. Confidence: 0.5
                  - Accumulate TCS again. Confidence: 0.8
                """;
        List<InsightClaim> claims = svc.parseClaims(insight(brief), Set.of("TCS"));
        assertEquals(1, claims.size());
        assertEquals(0.8, claims.getFirst().getConfidence(), 1e-9);
    }

    @Test
    void matchesOnlyWholeSymbolTokens() {
        // "INFY" must not be matched inside a longer token; "M&M" with punctuation must match.
        String brief = """
                - ⏳ Short-Term (0–7 days):
                  - Buy M&M ahead of auto sales. Confidence: 0.7
                  - Bullish on INFYTECHX (not a holding). Confidence: 0.6
                """;
        List<InsightClaim> claims = svc.parseClaims(insight(brief), Set.of("M&M", "INFY"));
        assertEquals(1, claims.size());
        assertEquals("M&M", claims.getFirst().getSymbol());
    }

    // ── Structured (BPR-3) ──────────────────────────────────────────────────────

    @Test
    void parsesStructuredClaimsBlockExactly() {
        String json = """
                {"claims": [
                  {"symbol": "TCS", "direction": "BULLISH", "horizon": "H5D",
                   "confidence": 0.72, "thesis": "order book strong", "expectedReturn": 0.08},
                  {"symbol": "RELIANCE", "direction": "BEARISH", "horizon": "H20D", "confidence": 0.6}
                ]}""";
        List<InsightClaim> claims =
                svc.buildStructuredClaims(insight("brief"), json, Set.of("TCS", "RELIANCE"));
        Map<String, InsightClaim> m = byKey(claims);

        assertEquals(2, claims.size());
        assertEquals(InsightClaim.Direction.BULLISH, m.get("TCS|H5D").getDirection());
        assertEquals(0.72, m.get("TCS|H5D").getConfidence(), 1e-9);
        assertEquals(0.08, m.get("TCS|H5D").getExpectedReturn(), 1e-9);
        assertEquals("order book strong", m.get("TCS|H5D").getThesis());
        assertEquals(InsightClaim.Direction.BEARISH, m.get("RELIANCE|H20D").getDirection());
        assertNull(m.get("RELIANCE|H20D").getExpectedReturn());
        assertEquals(InsightEvaluationService.PROMPT_VERSION, m.get("TCS|H5D").getPromptVersion());
    }

    @Test
    void structuredBlockFiltersNonPortfolioAndUnknownEnums() {
        String json = """
                {"claims": [
                  {"symbol": "ZZZZ", "direction": "BULLISH", "horizon": "H5D", "confidence": 0.7},
                  {"symbol": "TCS", "direction": "SIDEWAYS", "horizon": "H5D", "confidence": 0.7},
                  {"symbol": "TCS", "direction": "BULLISH", "horizon": "H99", "confidence": 0.7},
                  {"symbol": "TCS", "direction": "BULLISH", "horizon": "H5D", "confidence": 0.7}
                ]}""";
        List<InsightClaim> claims = svc.buildStructuredClaims(insight("b"), json, Set.of("TCS"));
        // Only the last (valid) claim survives: non-holding, bad direction, bad horizon dropped.
        assertEquals(1, claims.size());
        assertEquals("TCS", claims.getFirst().getSymbol());
    }

    @Test
    void structuredBlockDedupesKeepingHighestConfidence() {
        String json = """
                {"claims": [
                  {"symbol": "INFY", "direction": "BULLISH", "horizon": "H5D", "confidence": 0.5},
                  {"symbol": "INFY", "direction": "BULLISH", "horizon": "H5D", "confidence": 0.9}
                ]}""";
        List<InsightClaim> claims = svc.buildStructuredClaims(insight("b"), json, Set.of("INFY"));
        assertEquals(1, claims.size());
        assertEquals(0.9, claims.getFirst().getConfidence(), 1e-9);
    }

    @Test
    void unparseableJsonReturnsNullForRegexFallback() {
        assertNull(svc.buildStructuredClaims(insight("b"), "not json at all", Set.of("TCS")));
        assertNull(svc.buildStructuredClaims(insight("b"), "{\"oops\":", Set.of("TCS")));
    }

    @Test
    void tolerantToMarkdownFencedJson() {
        String json = "```json\n{\"claims\": [{\"symbol\":\"TCS\",\"direction\":\"BULLISH\","
                + "\"horizon\":\"H5D\",\"confidence\":0.7}]}\n```";
        List<InsightClaim> claims = svc.buildStructuredClaims(insight("b"), json, Set.of("TCS"));
        assertEquals(1, claims.size());
    }

    // ── Magnitude calibration (BPR-10) ──────────────────────────────────────────

    @Test
    void calibrationFoldsInMagnitudeErrorForReturnClaims() {
        // Two scored claims with stated expected returns; realised (rawReturn) differs.
        InsightClaim c1 = scored("TCS", 0.08, 0.05);  // expected +8%, realised +5% → err −3%
        InsightClaim c2 = scored("INFY", 0.04, 0.10); // expected +4%, realised +10% → err +6%

        var rows = svc.aggregate(List.of(c1, c2));
        assertEquals(1, rows.size());
        var row = rows.getFirst();
        assertEquals(2, row.magnitudeN());
        // MAE = (|−0.03| + |+0.06|)/2 = 0.045
        assertEquals(0.045, row.magnitudeMae(), 1e-9);
        // bias = (−0.03 + 0.06)/2 = +0.015 (claims slightly under-forecast on net)
        assertEquals(0.015, row.magnitudeBias(), 1e-9);
        assertTrue(row.render().contains("MAE="), "render surfaces the magnitude block");
    }

    @Test
    void magnitudeIgnoredWhenNoExpectedReturn() {
        // Directional-only claims (no expectedReturn) → magnitudeN stays 0.
        InsightClaim c = scored("TCS", null, 0.07);
        var rows = svc.aggregate(List.of(c));
        assertEquals(1, rows.size());
        assertEquals(0, rows.getFirst().magnitudeN());
        assertFalse(rows.getFirst().render().contains("MAE="));
    }

    /** A fully-scored claim fixture with optional expected and realised returns. */
    private InsightClaim scored(String symbol, Double expectedReturn, Double rawReturn) {
        return InsightClaim.builder()
                .insightId(1L).userId("u1").insightDate(LocalDate.of(2026, 6, 13))
                .symbol(symbol).direction(InsightClaim.Direction.BULLISH)
                .horizon(EventOutcome.Horizon.H20D).confidence(0.7)
                .provider("claude").promptVersion(InsightEvaluationService.PROMPT_VERSION)
                .expectedReturn(expectedReturn).rawReturn(rawReturn)
                .correct(Boolean.TRUE).scored(true)
                .build();
    }
}
