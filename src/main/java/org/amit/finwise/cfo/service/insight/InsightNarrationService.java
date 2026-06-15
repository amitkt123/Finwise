package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * B3 — constrained narration + honesty validator.
 *
 * One LLM call per card (refinement provider, not the expensive brief provider),
 * followed by sentence-level validation: any sentence whose numeric token is not
 * within ROUNDING_TOLERANCE of an allowed Computation value is stripped.
 * The LLM can describe; it cannot be the source of truth for a number.
 * Falls back to a template when the validated text is empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightNarrationService {

    @Qualifier("refinementLlmProvider")
    private final LLMProvider llm;

    private static final Pattern NUMBER_TOKEN =
            Pattern.compile("\\b\\d{1,3}(?:[,\\d{3}])*(?:\\.\\d+)?\\b");
    private static final double ROUNDING_TOLERANCE_REL = 0.01;  // ±1% relative
    private static final double ROUNDING_TOLERANCE_ABS = 0.005; // floor for near-zero

    private static final String SYSTEM_PROMPT =
            "You are a concise financial commentary writer for an Indian retail investor. "
            + "Rules:\n"
            + "- Write 2-3 sentences explaining what the given computed metrics mean in plain language.\n"
            + "- Every number you use must appear exactly in the 'Computed figures' list — "
            + "do not round differently, recalculate, or invent figures.\n"
            + "- No markdown headers. No bullet points. Flowing prose only.";

    public String narrate(InsightCard card) {
        if (card.getNumbers() == null || card.getNumbers().isEmpty()) {
            return card.getHeadline();
        }
        try {
            String figures = card.getNumbers().stream()
                    .map(c -> c.method() + " = " + c.value()
                            + (c.unit() != null && !c.unit().isBlank() ? " " + c.unit() : ""))
                    .collect(Collectors.joining("; "));

            String prompt = "Card: " + card.getTitle()
                    + "\nHeadline: " + card.getHeadline()
                    + "\nComputed figures (use these values only): " + figures
                    + "\n\nWrite 2-3 sentences of plain-language interpretation for the investor.";

            String raw = llm.chat(SYSTEM_PROMPT, prompt);
            String validated = validate(raw, card.getNumbers());
            return validated.isBlank() ? fallback(card) : validated;
        } catch (Exception e) {
            log.warn("[InsightNarration] LLM failed for '{}': {}", card.getTitle(), e.getMessage());
            return fallback(card);
        }
    }

    /**
     * Strips sentences containing numeric tokens not present in the allow-list.
     * Package-private for unit testing.
     */
    String validate(String text, List<Computation> allowList) {
        if (text == null || text.isBlank()) return "";
        Set<Double> allowed = allowList.stream()
                .map(Computation::value)
                .collect(Collectors.toSet());

        // Split on sentence boundaries preserving the delimiter
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder out = new StringBuilder();
        for (String sentence : sentences) {
            if (sentenceIsClean(sentence, allowed)) {
                if (!out.isEmpty()) out.append(' ');
                out.append(sentence.trim());
            }
        }
        return out.toString();
    }

    private boolean sentenceIsClean(String sentence, Set<Double> allowed) {
        Matcher m = NUMBER_TOKEN.matcher(sentence);
        while (m.find()) {
            String raw = m.group().replace(",", "");
            try {
                double token = Double.parseDouble(raw);
                if (!isAllowed(token, allowed)) return false;
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private boolean isAllowed(double token, Set<Double> allowed) {
        for (double a : allowed) {
            double tolerance = Math.abs(a) * ROUNDING_TOLERANCE_REL + ROUNDING_TOLERANCE_ABS;
            if (Math.abs(a - token) <= tolerance) return true;
        }
        return false;
    }

    private String fallback(InsightCard card) {
        if (card.getNumbers() == null || card.getNumbers().isEmpty()) {
            return card.getHeadline();
        }
        Computation first = card.getNumbers().get(0);
        String unitStr = (first.unit() != null && !first.unit().isBlank()) ? " " + first.unit() : "";
        return card.getHeadline() + " [" + first.method() + ": " + first.value() + unitStr + "]";
    }
}
