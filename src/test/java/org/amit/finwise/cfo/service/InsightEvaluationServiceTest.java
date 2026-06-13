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
        assertEquals(0.8, claims.get(0).getConfidence(), 1e-9);
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
        assertEquals("M&M", claims.get(0).getSymbol());
    }
}
