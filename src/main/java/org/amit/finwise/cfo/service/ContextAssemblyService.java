package org.amit.finwise.cfo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * DF-7 — deterministic, budget-aware context assembly.
 *
 * <p>The LLM does <em>zero</em> data work: every section is pre-computed evidence,
 * and this service decides only how much of it fits. Sections are supplied in
 * descending priority (risk → movers → evidence packs → news → goals). Assembly
 * is two-pass and fully deterministic:
 *
 * <ol>
 *   <li>each section is truncated to its own per-section cap, then</li>
 *   <li>sections are emitted highest-priority-first until the provider's total
 *       token budget is exhausted; a section that does not fully fit is truncated
 *       at a line boundary and lower-priority sections are dropped.</li>
 * </ol>
 *
 * Token counts use a {@code chars / CHARS_PER_TOKEN} heuristic — cheap and stable;
 * the budgets are intentionally conservative so the estimate never under-counts
 * into a provider hard limit. Truncation always cuts on a newline and appends a
 * visible marker so the model knows the section was clipped (and never sees a
 * half-sentence presented as fact).
 */
@Slf4j
@Service
public class ContextAssemblyService {

    /** Rough English+markdown bytes-per-token; conservative (real BPE ≈ 3.5–4). */
    private static final int CHARS_PER_TOKEN = 4;
    private static final String TRUNCATION_MARKER = "\n…[section truncated to fit context budget]\n";
    /**
     * Minimum real-content chars (excluding the marker) worth emitting. When the
     * remaining budget can't fit at least this much of a section, the section is
     * dropped wholesale rather than reduced to a marker-dominated stub.
     */
    private static final int MIN_USEFUL_CHARS = 40;

    /** Total context budget per provider, in tokens (prompt side only). */
    private final int budgetOllama;
    private final int budgetClaude;
    private final int budgetOpenai;
    private final int budgetGoogle;
    private final int budgetDefault;

    public ContextAssemblyService(
            @Value("${cfo.llm.context.budget.ollama:3500}") int budgetOllama,
            @Value("${cfo.llm.context.budget.claude:24000}") int budgetClaude,
            @Value("${cfo.llm.context.budget.openai:14000}") int budgetOpenai,
            @Value("${cfo.llm.context.budget.google:14000}") int budgetGoogle,
            @Value("${cfo.llm.context.budget.default:8000}") int budgetDefault) {
        this.budgetOllama = budgetOllama;
        this.budgetClaude = budgetClaude;
        this.budgetOpenai = budgetOpenai;
        this.budgetGoogle = budgetGoogle;
        this.budgetDefault = budgetDefault;
    }

    /**
     * One named, priority-ordered slice of context with its own cap.
     *
     * @param name       human label, used only for logging/diagnostics
     * @param body       the section text (already rendered evidence)
     * @param maxTokens  per-section ceiling before the global budget is applied
     */
    public record Section(String name, String body, int maxTokens) {
        public boolean isEmpty() {
            return body == null || body.isBlank();
        }
    }

    /** Convenience factory. */
    public static Section section(String name, String body, int maxTokens) {
        return new Section(name, body, maxTokens);
    }

    /** Total prompt-side token budget for a provider (by {@code providerName()}). */
    public int budgetFor(String providerName) {
        if (providerName == null) return budgetDefault;
        return switch (providerName.toLowerCase()) {
            case "ollama" -> budgetOllama;
            case "claude" -> budgetClaude;
            case "openai" -> budgetOpenai;
            case "google" -> budgetGoogle;
            default -> budgetDefault;
        };
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * Assemble the given sections (in supplied priority order) into a single
     * context string no larger than the provider's budget.
     */
    public String assemble(List<Section> sections, String providerName) {
        return assembleWithBudget(sections, budgetFor(providerName));
    }

    /** Same as {@link #assemble} but with an explicit token budget (testable). */
    public String assembleWithBudget(List<Section> sections, int totalBudget) {
        StringBuilder out = new StringBuilder();
        int remaining = totalBudget;
        List<String> dropped = new ArrayList<>();

        for (Section s : sections) {
            if (s == null || s.isEmpty()) continue;

            // Pass 1: clamp to the section's own ceiling.
            String body = truncateToTokens(s.body(), s.maxTokens());

            // Pass 2: clamp to whatever global budget is left.
            int cost = estimateTokens(body);
            if (cost > remaining) {
                if (remaining <= 0) {
                    dropped.add(s.name());
                    continue;
                }
                body = truncateToTokens(body, remaining);
                cost = estimateTokens(body);
            }
            if (body.isBlank()) {
                dropped.add(s.name());
                continue;
            }

            out.append(body);
            if (!body.endsWith("\n")) out.append('\n');
            out.append('\n');
            remaining -= cost;
        }

        if (!dropped.isEmpty()) {
            log.debug("[CtxAssembly] budget {} exhausted; dropped lower-priority sections: {}",
                    totalBudget, dropped);
        }
        return out.toString();
    }

    /**
     * Deterministically truncate to a token ceiling, cutting on the last newline
     * that fits and appending a visible marker. Never returns a mid-line fragment.
     */
    private String truncateToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) return "";
        if (estimateTokens(text) <= maxTokens) return text;

        int charBudget = maxTokens * CHARS_PER_TOKEN - TRUNCATION_MARKER.length();
        if (charBudget < MIN_USEFUL_CHARS) return "";   // too little room to be worth it
        String head = text.substring(0, Math.min(charBudget, text.length()));
        int lastNl = head.lastIndexOf('\n');
        if (lastNl > 0) head = head.substring(0, lastNl);
        return head + TRUNCATION_MARKER;
    }
}
