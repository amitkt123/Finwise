package org.amit.finwise.cfo.service.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Constrained narration + honesty validator (Honest-Insights Phase B3).
 *
 * <p>The LLM is allowed to write <em>interpretation only</em>. One call per brief sends the
 * cards as JSON and asks for a 1–2 sentence reading per card id, with an explicit instruction
 * to introduce no number, %, or ₹ figure not already present in that card's computations.
 *
 * <p>The instruction is not trusted — it is <b>enforced</b>. {@link #validate} tokenizes every
 * numeric in the returned narrative and drops any sentence containing a number that does not
 * match (within rounding tolerance) one of the card's Java-authored figures. This is the
 * structural guarantee: the LLM can no longer be the source of truth for a number. A card whose
 * narrative is fully stripped falls back to a templated interpretation built from its own title.
 */
@Slf4j
@Service
public class InsightNarrationService {

    private final LLMProvider llmProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // A numeric token: optional ₹, digits with thousands separators, optional decimals, optional %.
    private static final Pattern NUMERIC = Pattern.compile("₹?\\s?-?\\d[\\d,]*(?:\\.\\d+)?%?");
    // Relative tolerance so "40.0%" in a computation matches "40%" in the narrative.
    private static final double REL_TOL = 1e-3;

    public InsightNarrationService(@Qualifier("refinementLlmProvider") LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    /**
     * Return the cards with a validated {@code narrative} populated on each. Best-effort: if the
     * LLM call or its parse fails, every card falls back to a templated interpretation, so the
     * brief always renders. Never throws into brief generation.
     */
    public List<InsightCard> narrate(List<InsightCard> cards) {
        if (cards == null || cards.isEmpty()) return List.of();

        Map<String, String> raw;
        try {
            String json = llmProvider.chatJson(SYSTEM_PROMPT, buildUserPrompt(cards));
            raw = parseNarratives(json);
        } catch (Exception e) {
            log.warn("[InsightCards] narration LLM call failed, using templated fallbacks: {}",
                    e.getMessage());
            raw = Map.of();
        }

        List<InsightCard> out = new ArrayList<>(cards.size());
        for (InsightCard card : cards) {
            String candidate = raw.get(card.id());
            String narrative = validate(candidate, card);
            if (narrative == null || narrative.isBlank()) narrative = templatedFallback(card);
            out.add(withNarrative(card, narrative));
        }
        return out;
    }

    /**
     * Strip any sentence in {@code candidate} that contains a numeric not matching one of the
     * card's Java-authored figures. Returns the surviving sentences joined, or null when nothing
     * survives (the caller then uses the templated fallback). Pure and unit-testable.
     */
    String validate(String candidate, InsightCard card) {
        if (candidate == null || candidate.isBlank()) return null;
        Set<Double> allowed = allowedNumbers(card);

        List<String> kept = new ArrayList<>();
        for (String sentence : candidate.split("(?<=[.!?])\\s+")) {
            if (sentence.isBlank()) continue;
            if (numericsAllowed(sentence, allowed)) {
                kept.add(sentence.strip());
            } else {
                log.debug("[InsightCards] stripped hallucinated-number sentence on card {}: {}",
                        card.id(), sentence.strip());
            }
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    /** True when every numeric token in the sentence matches an allowed Java-authored figure. */
    private boolean numericsAllowed(String sentence, Set<Double> allowed) {
        Matcher m = NUMERIC.matcher(sentence);
        while (m.find()) {
            Double v = parseNumeric(m.group());
            if (v == null) continue;
            if (allowed.stream().noneMatch(a -> close(a, v))) return false;
        }
        return true;
    }

    /** All numeric values the LLM is permitted to restate: from the card's title + computations. */
    private Set<Double> allowedNumbers(InsightCard card) {
        Set<Double> nums = new LinkedHashSet<>();
        collectNumbers(card.title(), nums);
        if (card.computations() != null) {
            for (Computation c : card.computations()) {
                collectNumbers(c.value(), nums);
                collectNumbers(c.window(), nums);   // dates/obs counts are Java-authored too
            }
        }
        return nums;
    }

    private void collectNumbers(String text, Set<Double> into) {
        if (text == null) return;
        Matcher m = NUMERIC.matcher(text);
        while (m.find()) {
            Double v = parseNumeric(m.group());
            if (v != null) into.add(v);
        }
    }

    private static Double parseNumeric(String token) {
        String cleaned = token.replace("₹", "").replace(",", "").replace("%", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean close(double a, double b) {
        double diff = Math.abs(a - b);
        return diff <= Math.max(1e-9, REL_TOL * Math.max(Math.abs(a), Math.abs(b)));
    }

    /** Deterministic, number-free interpretation used when the LLM is unavailable or stripped. */
    private static String templatedFallback(InsightCard card) {
        return card.title() == null ? card.category() + " insight." : card.title() + ".";
    }

    // ── LLM plumbing ────────────────────────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            You write one-to-two sentence interpretations of pre-computed financial insight cards.
            STRICT RULE: do NOT introduce any number, percentage, or ₹ figure that is not already
            present in that card's computations. You may reference figures verbatim, but never
            invent, round, or derive a new one. Output ONLY a JSON object mapping each card id to
            its narrative string — no prose, no markdown fences.""";

    private String buildUserPrompt(List<InsightCard> cards) {
        StringBuilder sb = new StringBuilder("Cards (JSON):\n");
        List<Map<String, Object>> payload = new ArrayList<>();
        for (InsightCard c : cards) {
            List<Map<String, String>> comps = new ArrayList<>();
            if (c.computations() != null) {
                for (Computation comp : c.computations()) {
                    comps.add(Map.of(
                            "label", nz(comp.label()), "value", nz(comp.value()),
                            "method", nz(comp.method()), "window", nz(comp.window())));
                }
            }
            payload.add(Map.of(
                    "id", nz(c.id()),
                    "title", nz(c.title()),
                    "category", c.category().toString(),
                    "computations", comps,
                    "caveats", c.caveats() == null ? List.of() : c.caveats()));
        }
        try {
            sb.append(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            sb.append("[]");
        }
        sb.append("\n\nReturn {\"cardId\": \"narrative\", ...}.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseNarratives(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            String s = raw.trim();
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();
            Map<String, Object> parsed = objectMapper.readValue(s.substring(start, end + 1), Map.class);
            Map<String, String> out = new java.util.HashMap<>();
            parsed.forEach((k, v) -> { if (v != null) out.put(k, v.toString()); });
            return out;
        } catch (Exception e) {
            log.debug("[InsightCards] narration JSON unparseable: {}", e.getMessage());
            return Map.of();
        }
    }

    private InsightCard withNarrative(InsightCard c, String narrative) {
        return InsightCard.builder(c.id(), c.category(), c.severity())
                .title(c.title()).actionVerb(c.actionVerb()).symbol(c.symbol())
                .computations(c.computations()).caveats(c.caveats())
                .rawConfidence(c.rawConfidence()).calibratedConfidence(c.calibratedConfidence())
                .trackRecord(c.trackRecord()).narrative(narrative)
                .build();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
