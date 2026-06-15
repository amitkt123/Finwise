package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.springframework.stereotype.Component;

/**
 * B1 — renders InsightCard objects as markdown blocks and machine-parseable action lines.
 *
 * The renderer owns all number formatting. Numbers pass through byte-for-byte from the
 * Computation record; no rounding or reformatting that changes the token value.
 * Action lines use the format already consumed by InsightEvaluationService.extractClaims:
 *   - SYMBOL verb: reason — Confidence: 0.X
 */
@Component
public class InsightCardRenderer {

    private static final String INSUFFICIENT_DATA_HEADER =
            "> **Insufficient data** — need more price history to compute this insight.\n";

    public String renderCard(InsightCard card) {
        if (card.getNumbers() == null || card.getNumbers().isEmpty()) {
            return renderInsufficient(card);
        }

        StringBuilder sb = new StringBuilder();
        String badge = card.getSeverity() == InsightCard.Severity.ALERT ? "⚠️ ALERT" : "ℹ️ INFO";
        sb.append("### [").append(badge).append("] ").append(card.getTitle()).append("\n");
        sb.append("> ").append(card.getHeadline()).append("\n\n");

        sb.append("| Metric | Value | Window |\n");
        sb.append("|--------|-------|--------|\n");
        for (Computation c : card.getNumbers()) {
            sb.append("| ").append(c.method())
              .append(" | ").append(formatValue(c))
              .append(" | ").append(c.window() != null ? c.window() : "—")
              .append(" |\n");
        }
        sb.append("\n");

        if (card.getNarration() != null && !card.getNarration().isBlank()) {
            sb.append(card.getNarration()).append("\n");
        }

        return sb.toString();
    }

    private String renderInsufficient(InsightCard card) {
        return "### " + card.getTitle() + "\n" + INSUFFICIENT_DATA_HEADER;
    }

    /**
     * Renders the machine-parseable action line consumed by InsightEvaluationService.
     * Format: - SYMBOL verb: reason — Confidence: 0.X
     * Numbers in the reason string must already be Java-authored.
     */
    public String renderActionLine(String symbol, String verb, String reason, double confidence) {
        return String.format("- %s %s: %s — Confidence: %.1f", symbol, verb, reason, confidence);
    }

    /** Formats a Computation value according to its unit, preserving the exact decimal representation. */
    static String formatValue(Computation c) {
        String unit = c.unit() != null ? c.unit() : "";
        return switch (unit) {
            case "₹"  -> String.format("₹%,.0f", c.value());
            case "%"  -> String.format("%.4f%%", c.value());
            case "x"  -> String.format("%.4f×", c.value());
            default   -> String.format("%.4f", c.value());
        };
    }
}
