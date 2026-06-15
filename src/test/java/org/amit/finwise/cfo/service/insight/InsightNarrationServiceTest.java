package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The honesty validator (Phase B3): the LLM may interpret but never introduce a number absent
 * from the card's Java-authored computations. A sentence carrying a hallucinated figure is
 * stripped; a faithful sentence survives; a card with nothing left falls back to a template.
 */
class InsightNarrationServiceTest {

    private final InsightNarrationService service =
            new InsightNarrationService(Mockito.mock(LLMProvider.class));

    private InsightCard card() {
        return InsightCard.builder("risk-budget-HDFCBANK",
                        InsightCard.Category.RISK_BUDGET, InsightCard.Severity.ACTION)
                .title("Trim HDFCBANK — 40% of portfolio volatility")
                .computations(List.of(
                        new Computation("% of portfolio volatility", "40.0%", "CCR/σ", "returns", "740d"),
                        new Computation("Beta vs Nifty 50", "1.16", "OLS", "returns", "740d")))
                .rawConfidence(0.7)
                .build();
    }

    @Test
    void stripsSentenceWithHallucinatedNumber() {
        String narrative = "HDFCBANK drives 40% of portfolio volatility. "
                + "It could fall ₹52000 next week.";   // ₹52000 absent from the card → must be stripped

        String validated = service.validate(narrative, card());

        assertNotNull(validated);
        assertTrue(validated.contains("40%"), "faithful sentence must survive");
        assertFalse(validated.contains("52000"), "hallucinated number sentence must be stripped");
    }

    @Test
    void keepsFaithfulNarrativeIntact() {
        String narrative = "HDFCBANK is the top risk contributor at 40.0% with a beta of 1.16.";
        String validated = service.validate(narrative, card());
        assertEquals(narrative, validated);
    }

    @Test
    void toleratesRoundingDifferencesInFormatting() {
        // narrative restates "40.0%" as "40%" — same value, different formatting → kept.
        String validated = service.validate("Concentration sits near 40% of risk.", card());
        assertNotNull(validated);
        assertTrue(validated.contains("40%"));
    }

    @Test
    void returnsNullWhenEverySentenceIsHallucinated() {
        String narrative = "Expect a ₹99000 drawdown. Volatility is 73%.";
        assertNull(service.validate(narrative, card()));
    }

    @Test
    void keepsNumberFreeInterpretation() {
        String narrative = "This is your single largest source of risk; consider trimming.";
        assertEquals(narrative, service.validate(narrative, card()));
    }
}
