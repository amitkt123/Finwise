package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the honesty validator in InsightNarrationService.
 * The LLM provider is not needed — only validate() is exercised here.
 */
class InsightNarrationServiceTest {

    // Construct with null LLM provider — validate() doesn't use it.
    private final InsightNarrationService svc = new InsightNarrationService(null);

    private List<Computation> allow(double... values) {
        List<Computation> list = new java.util.ArrayList<>();
        for (double v : values) {
            list.add(new Computation(v, "metric", List.of(), null, "%"));
        }
        return list;
    }

    // ── hallucinated number is stripped ───────────────────────────────────────

    @Test
    void stripsHallucinatedNumber() {
        // Allowed: 17.4 and 1.2.  "42" is not in the allow-list.
        String text = "The portfolio vol is 17.4%. The beta is 1.2. The VaR is 42.";
        String validated = svc.validate(text, allow(17.4, 1.2));

        assertFalse(validated.contains("42"),
                "Sentence with hallucinated number 42 should be stripped");
        assertTrue(validated.contains("17.4"),
                "Sentence with allowed number 17.4 should remain");
        assertTrue(validated.contains("1.2"),
                "Sentence with allowed number 1.2 should remain");
    }

    // ── rounding-tolerant: ±1% relative is accepted ───────────────────────────

    @Test
    void roundingWithinToleranceIsAccepted() {
        // Allowed: 17.4 exactly.  17.5 is within 1% of 17.4 (diff = 0.1, 1% of 17.4 = 0.174).
        String text = "The portfolio vol is 17.5%.";
        String validated = svc.validate(text, allow(17.4));

        assertFalse(validated.isBlank(),
                "Sentence within rounding tolerance should NOT be stripped");
    }

    // ── clean text passes through unchanged ───────────────────────────────────

    @Test
    void cleanTextPassesThrough() {
        // No numeric tokens in the sentence.
        String text = "The portfolio is concentrated in the financial sector.";
        String validated = svc.validate(text, allow(35.0));
        assertEquals(text, validated);
    }

    // ── multiple sentences: bad sentence removed, others kept ─────────────────

    @Test
    void onlyBadSentenceRemoved() {
        String text = "Vol is 17.4%. The sky is blue. Drawdown is 99.9%.";
        String validated = svc.validate(text, allow(17.4));

        assertTrue(validated.contains("17.4"), "Allowed sentence should remain");
        assertTrue(validated.contains("sky is blue"), "No-number sentence should remain");
        assertFalse(validated.contains("99.9"), "Hallucinated number should be stripped");
    }

    // ── empty/null input → empty output ───────────────────────────────────────

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", svc.validate("", allow(1.0)));
        assertEquals("", svc.validate(null, allow(1.0)));
    }
}
