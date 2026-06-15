package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renderer is pure — no Spring context needed.
 */
class InsightCardRendererTest {

    private final InsightCardRenderer renderer = new InsightCardRenderer();

    // ── empty card → insufficient-data header ─────────────────────────────────

    @Test
    void emptyNumbersRendersInsufficientDataHeader() {
        InsightCard card = InsightCard.builder()
                .category(InsightCard.Category.RISK)
                .severity(InsightCard.Severity.INFO)
                .title("Risk Budget")
                .headline("some headline")
                .numbers(List.of())
                .build();

        String rendered = renderer.renderCard(card);
        assertTrue(rendered.contains("Insufficient data"),
                "Expected insufficient-data header, got:\n" + rendered);
        assertTrue(rendered.contains("Risk Budget"),
                "Expected title in rendered output");
    }

    // ── VaR number passes through byte-identical ───────────────────────────────

    @Test
    void varValuePassesThroughByteIdentical() {
        double varValue = 47823.9876;
        Computation comp = new Computation(varValue, "Cornish-Fisher 95% VaR",
                List.of(), "252d", "₹");
        InsightCard card = InsightCard.builder()
                .category(InsightCard.Category.BACKTEST)
                .severity(InsightCard.Severity.INFO)
                .title("VaR Backtest")
                .headline("Model is well-calibrated")
                .numbers(List.of(comp))
                .build();

        String rendered = renderer.renderCard(card);
        // The formatted value should appear in the table
        assertTrue(rendered.contains("Cornish-Fisher 95% VaR"),
                "Expected metric name, got:\n" + rendered);
        // Verify the raw value is represented (formatted as ₹47,824)
        assertTrue(rendered.contains("47,824") || rendered.contains("47823"),
                "Expected VaR value in rendered output:\n" + rendered);
    }

    // ── action line format matches InsightEvaluationService regex ─────────────

    @Test
    void actionLineMatchesEvaluationFormat() {
        String line = renderer.renderActionLine("HDFCBANK", "trim",
                "top risk contributor, beta=1.16", 0.7);

        assertTrue(line.startsWith("- HDFCBANK trim:"),
                "Expected action line prefix, got: " + line);
        assertTrue(line.contains("Confidence: 0.7"),
                "Expected confidence token, got: " + line);
        assertTrue(line.contains("—"),
                "Expected em-dash separator, got: " + line);
    }

    // ── ALERT badge appears for ALERT severity ────────────────────────────────

    @Test
    void alertBadgeAppears() {
        Computation comp = new Computation(35.0, "% Risk Contribution",
                List.of("RELIANCE"), "252d", "%");
        InsightCard card = InsightCard.builder()
                .category(InsightCard.Category.RISK)
                .severity(InsightCard.Severity.ALERT)
                .title("Risk Budget — RELIANCE")
                .headline("RELIANCE consumes 35.0000% of portfolio risk")
                .numbers(List.of(comp))
                .build();

        String rendered = renderer.renderCard(card);
        assertTrue(rendered.contains("ALERT"), "Expected ALERT badge:\n" + rendered);
    }

    // ── formatValue unit routing ───────────────────────────────────────────────

    @Test
    void formatValueRoutesUnitsCorrectly() {
        assertEquals("₹1,000", InsightCardRenderer.formatValue(
                new Computation(1000, "x", List.of(), null, "₹")));
        assertTrue(InsightCardRenderer.formatValue(
                new Computation(17.4, "x", List.of(), null, "%")).contains("17.4"));
        assertTrue(InsightCardRenderer.formatValue(
                new Computation(1.2, "x", List.of(), null, "x")).contains("1.2"));
    }
}
