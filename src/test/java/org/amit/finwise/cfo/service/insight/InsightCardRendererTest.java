package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renderer guarantees (Phase B1): numbers are passed through byte-for-byte, actionable cards
 * emit the machine-parseable claim line, and an empty card set still renders cleanly with an
 * explicit insufficient-data note (never a crash, never a fabricated number).
 */
class InsightCardRendererTest {

    private final InsightCardRenderer renderer = new InsightCardRenderer();

    @Test
    void rendersVarValueByteIdenticalToEngineFormat() {
        double var95Cf = 48123.7;                         // stand-in for rd.var95CornishFisher()
        String engineString = String.format("₹%.0f", var95Cf);   // exactly what the engine renders

        InsightCard card = InsightCard.builder("var-backtest",
                        InsightCard.Category.VAR_BACKTEST, InsightCard.Severity.INFO)
                .title("VaR backtest: VaR well-calibrated")
                .computations(List.of(new Computation(
                        "1-Day VaR 95% (Cornish-Fisher)", engineString,
                        "z·σ·V", "daily returns", "740d")))
                .rawConfidence(0.75)
                .build();

        String md = renderer.renderCard(card);
        assertTrue(md.contains(engineString),
                "rendered card must contain the engine VaR string verbatim: " + engineString);
        // The renderer must not reformat the figure into a different representation.
        assertTrue(md.contains("₹48124"));
        assertFalse(md.contains("48123.7"));
    }

    @Test
    void emitsMachineParseableActionLineForActionableCard() {
        InsightCard card = InsightCard.builder("risk-budget-HDFCBANK",
                        InsightCard.Category.RISK_BUDGET, InsightCard.Severity.ACTION)
                .title("Trim HDFCBANK — top risk contributor")
                .symbol("HDFCBANK")
                .actionVerb("trim")
                .rawConfidence(0.7)
                .build();

        String line = renderer.actionLine(card);
        assertNotNull(line);
        assertEquals("- HDFCBANK trim: Trim HDFCBANK — top risk contributor — Confidence: 0.7", line);
    }

    @Test
    void actionLineUsesCalibratedConfidenceWhenPresent() {
        InsightCard card = InsightCard.builder("risk-budget-X",
                        InsightCard.Category.RISK_BUDGET, InsightCard.Severity.ACTION)
                .title("Trim X").symbol("X").actionVerb("trim")
                .rawConfidence(0.7).calibratedConfidence(0.5).build();

        assertTrue(renderer.actionLine(card).endsWith("Confidence: 0.5"));
    }

    @Test
    void noActionLineForNonActionableCard() {
        InsightCard card = InsightCard.builder("concentration",
                        InsightCard.Category.CONCENTRATION, InsightCard.Severity.INFO)
                .title("Diversification: 8.0 effective bets")
                .rawConfidence(0.7)
                .build();

        assertNull(renderer.actionLine(card));
    }

    @Test
    void emptyCardSetRendersInsufficientDataHeaderNotACrash() {
        String md = renderer.toMarkdownBrief("## Insight Cards", List.of());
        assertTrue(md.contains("## Insight Cards"));
        assertTrue(md.toLowerCase().contains("insufficient data"));
    }
}
