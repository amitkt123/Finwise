package org.amit.finwise.policy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.policy.model.PolicyArea;
import org.amit.finwise.policy.model.PolicyImpactDirection;
import org.amit.finwise.policy.model.PolicyImpactHorizon;
import org.amit.finwise.policy.model.PolicySubjectType;
import org.amit.finwise.policy.service.PolicyIntelligenceService.PolicyImpactDraft;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * LLM-driven policy impact extraction (Roadmap Phase 2.4).
 *
 * Replaces the ~600 lines of {@code haystack.contains(...)} keyword rules with a
 * structured LLM pass: the model reads the document and emits a list of impacts in
 * the {@code PolicyImpact} vocabulary (subject type/key, direction, horizon,
 * confidence, summary, reasoning). The deterministic rule extractor in
 * {@link PolicyDocumentCrawlerService} remains the fallback whenever this is
 * disabled, the provider is unavailable, or the response can't be parsed —
 * so behaviour is never worse than the rule-only baseline.
 *
 * Opt-in via {@code cfo.policy.llm-extraction.enabled=true}.
 */
@Slf4j
@Service
public class PolicyImpactExtractionService {

    private final LLMProvider llmProvider;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Value("${cfo.policy.llm-extraction.enabled:false}")
    private boolean enabled;

    /** Cap the text sent to the model to keep prompts bounded. */
    @Value("${cfo.policy.llm-extraction.max-chars:8000}")
    private int maxChars;

    public PolicyImpactExtractionService(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    private static final String SYSTEM_PROMPT = """
            You are a financial-policy analyst. Read an Indian regulatory/policy document
            (RBI, SEBI, PIB, Ministry of Finance) and identify which markets it affects.

            Return ONLY a JSON object: {"impacts": [ ... ]}. Each impact object has:
              subjectType   one of: SECTOR, ASSET_CLASS, FACTOR, INSTRUMENT_TYPE, MARKET_STRUCTURE,
                            TAX_TOPIC, REGULATED_ENTITY_TYPE, INVESTOR_TYPE, PRODUCT, MACRO_FACTOR, THEME
              subjectKey    short canonical key, e.g. "Banking", "Debt", "Rate Sensitive", "capital-gains"
              subjectLabel  human-readable label for the subject
              direction     one of: POSITIVE, NEGATIVE, MIXED, NEUTRAL  (effect on that subject)
              horizon       one of: IMMEDIATE, SHORT_TERM, MEDIUM_TERM, LONG_TERM, STRUCTURAL
              confidence    number 0.0-1.0
              impactSummary one sentence: what changes for that subject and why
              reasoning     one sentence: the transmission mechanism

            Rules:
              - Only include impacts the document actually supports; do not speculate.
              - Use SECTOR keys consistent with Indian markets (Banking, IT, Pharma, Auto, FMCG,
                Energy, Metals, Realty, Telecom, Infrastructure, Capital Markets).
              - Prefer 1-5 high-signal impacts over many weak ones.
              - If the document has no material market impact, return {"impacts": []}.
            """;

    /**
     * Extract impacts via the LLM. Returns empty when disabled, unavailable, or
     * unparseable — signalling the caller to use its rule-based fallback.
     */
    public Optional<List<PolicyImpactDraft>> extract(PolicyArea policyArea,
                                                     String title,
                                                     String text,
                                                     LocalDate effectiveDate) {
        if (!enabled) return Optional.empty();
        if (text == null || text.isBlank()) return Optional.empty();

        String body = text.length() > maxChars ? text.substring(0, maxChars) : text;
        String userMessage = "TITLE: " + (title != null ? title : "")
                + "\n\nDOCUMENT:\n" + body;

        try {
            String raw = llmProvider.chatJson(SYSTEM_PROMPT, userMessage);
            if (raw == null || raw.isBlank()) return Optional.empty();
            String json = isolateJson(raw);
            JsonNode root = objectMapper.readTree(json);
            JsonNode impacts = root.path("impacts");
            if (!impacts.isArray()) return Optional.empty();

            List<PolicyImpactDraft> drafts = new ArrayList<>();
            for (JsonNode node : impacts) {
                PolicyImpactDraft draft = toDraft(node, policyArea, effectiveDate);
                if (draft != null) drafts.add(draft);
            }
            if (drafts.isEmpty()) return Optional.empty();
            log.info("[Policy] LLM extracted {} impact(s) for '{}'", drafts.size(), title);
            return Optional.of(drafts);
        } catch (Exception e) {
            log.warn("[Policy] LLM impact extraction failed ('{}'): {} — falling back to rules",
                    title, e.getMessage());
            return Optional.empty();
        }
    }

    private PolicyImpactDraft toDraft(JsonNode node, PolicyArea policyArea, LocalDate effectiveDate) {
        String subjectKey = textOrNull(node, "subjectKey");
        String impactSummary = textOrNull(node, "impactSummary");
        if (subjectKey == null || impactSummary == null) return null;

        PolicySubjectType subjectType = parseEnum(
                PolicySubjectType.class, textOrNull(node, "subjectType"), PolicySubjectType.OTHER);
        PolicyImpactDirection direction = parseEnum(
                PolicyImpactDirection.class, textOrNull(node, "direction"), PolicyImpactDirection.MIXED);
        PolicyImpactHorizon horizon = parseEnum(
                PolicyImpactHorizon.class, textOrNull(node, "horizon"), PolicyImpactHorizon.MEDIUM_TERM);
        String subjectLabel = textOrNull(node, "subjectLabel");
        if (subjectLabel == null) subjectLabel = subjectKey;
        String reasoning = textOrNull(node, "reasoning");
        Double confidence = node.has("confidence") && node.get("confidence").isNumber()
                ? Math.max(0.0, Math.min(1.0, node.get("confidence").asDouble()))
                : 0.6;

        return new PolicyImpactDraft(
                policyArea,
                subjectType,
                subjectKey,
                subjectLabel,
                direction,
                horizon,
                effectiveDate,
                null,
                confidence,
                impactSummary,
                reasoning,
                List.of("llm-extracted"));
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return s.isBlank() ? null : s;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ENGLISH).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** Tolerantly pull the outermost JSON object out of a possibly fenced/prose-wrapped reply. */
    private String isolateJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl >= 0) s = s.substring(firstNl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
