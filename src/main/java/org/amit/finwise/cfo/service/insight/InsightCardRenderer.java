package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Renders {@link InsightCard}s to the markdown brief (Honest-Insights Phase B1).
 *
 * <p>Two responsibilities, both honesty-preserving:
 * <ol>
 *   <li>Emit a human-readable card block whose every number comes verbatim from the card's
 *       {@link Computation} values — the renderer never formats a figure itself, so a
 *       rendered VaR string is byte-identical to the engine's {@code String.format(...)}.</li>
 *   <li>Emit, for any actionable card (a non-null {@code actionVerb} + {@code symbol}), the
 *       machine-parseable action line {@code - SYMBOL verb: reason — Confidence: 0.X} that
 *       {@code InsightEvaluationService.extractClaims} already parses — so the calibration
 *       scoreboard keeps working unchanged.</li>
 * </ol>
 */
@Service
public class InsightCardRenderer {

    /**
     * Render the full brief: a header followed by the cards, highest-severity first.
     * When {@code cards} is empty the brief still renders cleanly with an explicit
     * insufficient-data note — never a crash and never a fabricated number.
     */
    public String toMarkdownBrief(String header, List<InsightCard> cards) {
        StringBuilder sb = new StringBuilder();
        if (header != null && !header.isBlank()) {
            sb.append(header.strip()).append("\n\n");
        }
        if (cards == null || cards.isEmpty()) {
            sb.append("_Insufficient data to compute insight cards for this portfolio "
                    + "(no aligned price history or no holdings). No figures are shown rather "
                    + "than fabricated ones._\n");
            return sb.toString();
        }
        for (InsightCard card : cards) {
            sb.append(renderCard(card)).append("\n");
        }
        return sb.toString();
    }

    /** Render a single card: heading, narrative, computation table, caveats, action line. */
    public String renderCard(InsightCard card) {
        StringBuilder sb = new StringBuilder();

        sb.append("### ").append(severityBadge(card.severity())).append(' ')
          .append(card.title() == null ? "" : card.title()).append('\n');

        sb.append("*").append(card.category()).append(" · Confidence: ")
          .append(formatConfidence(card.rawConfidence()));
        if (card.calibratedConfidence() != null) {
            sb.append(" (calibrated: ").append(formatConfidence(card.calibratedConfidence())).append(')');
        }
        sb.append("*\n");
        if (card.trackRecord() != null && !card.trackRecord().isBlank()) {
            sb.append("_Track record: ").append(card.trackRecord().strip()).append("_\n");
        }

        if (card.narrative() != null && !card.narrative().isBlank()) {
            sb.append('\n').append("> ").append(card.narrative().strip().replace("\n", "\n> ")).append('\n');
        }

        if (card.computations() != null && !card.computations().isEmpty()) {
            sb.append('\n');
            sb.append("| Metric | Value | Method | Inputs | Window |\n");
            sb.append("|---|---|---|---|---|\n");
            for (Computation c : card.computations()) {
                sb.append("| ").append(cell(c.label()))
                  .append(" | ").append(cell(c.value()))
                  .append(" | ").append(cell(c.method()))
                  .append(" | ").append(cell(c.inputs()))
                  .append(" | ").append(cell(c.window()))
                  .append(" |\n");
            }
        }

        if (card.caveats() != null && !card.caveats().isEmpty()) {
            sb.append('\n').append("Caveats: ").append(String.join("; ", card.caveats())).append('\n');
        }

        String actionLine = actionLine(card);
        if (actionLine != null) {
            sb.append('\n').append(actionLine).append('\n');
        }
        return sb.toString();
    }

    /**
     * The machine-parseable action line, or null for a non-actionable card. Format matches
     * the brief contract {@code - SYMBOL verb: reason — Confidence: 0.X}; the reason is the
     * card title so {@code extractClaims} sees the directional verb and the symbol token.
     * Surfaces the calibrated confidence when available so the scored claim matches what the
     * user reads.
     */
    String actionLine(InsightCard card) {
        if (card.actionVerb() == null || card.actionVerb().isBlank()
                || card.symbol() == null || card.symbol().isBlank()) {
            return null;
        }
        String reason = card.title() == null ? card.category().toString() : card.title();
        return String.format("- %s %s: %s — Confidence: %s",
                card.symbol(), card.actionVerb().toLowerCase(), reason,
                formatConfidence(card.effectiveConfidence()));
    }

    private static String severityBadge(InsightCard.Severity s) {
        return switch (s) {
            case ALERT -> "🔴 [ALERT]";
            case ACTION -> "🟠 [ACTION]";
            case WATCH -> "🟡 [WATCH]";
            case INFO -> "🔵 [INFO]";
        };
    }

    /** One decimal place, matching the existing "Confidence: 0.X" claim-extraction contract. */
    private static String formatConfidence(double c) {
        return String.format("%.1f", Math.max(0.0, Math.min(1.0, c)));
    }

    /** Escape pipes and collapse newlines so a value never breaks the markdown table row. */
    private static String cell(String v) {
        if (v == null || v.isBlank()) return "—";
        return v.replace("|", "\\|").replace("\n", " ").strip();
    }
}
