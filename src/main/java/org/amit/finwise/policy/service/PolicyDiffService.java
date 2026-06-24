package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.policy.model.PolicyChange;
import org.amit.finwise.policy.model.PolicyDocument;
import org.amit.finwise.policy.model.PolicyDocumentVersion;
import org.amit.finwise.policy.model.PolicyImpact;
import org.amit.finwise.policy.repository.PolicyChangeRepository;
import org.amit.finwise.policy.repository.PolicyDocumentRepository;
import org.amit.finwise.policy.repository.PolicyDocumentVersionRepository;
import org.amit.finwise.policy.repository.PolicyImpactRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 3.1 — LLM-powered policy diff on supersession.
 *
 * Accepts IDs (not detached entities) so it is safe to run async after the
 * ingest transaction commits. Each repository call is self-transactional.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyDiffService {

    private final PolicyChangeRepository changeRepository;
    private final PolicyImpactRepository impactRepository;
    private final PolicyDocumentRepository documentRepository;
    private final PolicyDocumentVersionRepository versionRepository;

    @Qualifier("refinementLlmProvider")
    private final LLMProvider llmProvider;

    private static final int MAX_CHARS_PER_VERSION = 4000;

    private static final String SYSTEM_PROMPT = """
            You are a policy analyst assistant. You will be given two versions of a financial
            regulatory document (OLD and NEW). Produce a JSON object with exactly two fields:
            - "summary": a single sentence (max 120 chars) capturing the most important change,
              e.g. "CRR raised from 4.0% to 4.5%, effective immediately."
            - "narrative": a 2-3 sentence plain-English explanation of what changed and why it
              matters to a retail investor in India. No jargon.
            Respond with ONLY the JSON object — no markdown, no preamble.
            """;

    @Async("llmRefinementExecutor")
    public void diffAsync(Long documentId, Long fromVersionId, Long toVersionId) {
        if (documentId == null || toVersionId == null) return;
        if (changeRepository.existsByToVersionId(toVersionId)) return;

        Optional<PolicyDocument> docOpt = documentRepository.findById(documentId);
        Optional<PolicyDocumentVersion> toVersionOpt = versionRepository.findById(toVersionId);
        if (docOpt.isEmpty() || toVersionOpt.isEmpty()) return;

        PolicyDocument document = docOpt.get();
        PolicyDocumentVersion toVersion = toVersionOpt.get();
        PolicyDocumentVersion fromVersion = fromVersionId != null
                ? versionRepository.findById(fromVersionId).orElse(null)
                : null;

        try {
            String summary;
            String narrative;

            if (fromVersion != null) {
                String oldText = truncate(fromVersion.getNormalizedText());
                String newText = truncate(toVersion.getNormalizedText());
                String userPrompt = "OLD VERSION:\n" + oldText + "\n\nNEW VERSION:\n" + newText;
                String json = llmProvider.chatJson(SYSTEM_PROMPT, userPrompt);
                summary = extractField(json, "summary");
                narrative = extractField(json, "narrative");
            } else {
                summary = null;
                narrative = null;
            }

            if (summary == null || summary.isBlank()) {
                summary = "Document updated to version " + toVersion.getVersionNumber();
            }

            List<PolicyImpact> impacts = impactRepository.findByVersion(toVersion);
            if (impacts.isEmpty()) {
                saveChange(document, fromVersion, toVersion, null, summary, narrative);
            } else {
                for (PolicyImpact impact : impacts) {
                    saveChange(document, fromVersion, toVersion, impact.getSubjectKey(), summary, narrative);
                }
            }

            log.info("[PolicyDiff] Generated diff for doc={} v{}→v{}",
                    document.getDocumentKey(),
                    fromVersion != null ? fromVersion.getVersionNumber() : "–",
                    toVersion.getVersionNumber());
        } catch (Exception e) {
            log.warn("[PolicyDiff] LLM diff failed for doc={}: {}", document.getDocumentKey(), e.getMessage());
            saveChange(document, fromVersion, toVersion, null,
                    "Document updated to version " + toVersion.getVersionNumber(), null);
        }
    }

    private void saveChange(PolicyDocument document,
                            PolicyDocumentVersion fromVersion,
                            PolicyDocumentVersion toVersion,
                            String subjectKey,
                            String summary,
                            String narrative) {
        changeRepository.save(PolicyChange.builder()
                .document(document)
                .fromVersion(fromVersion)
                .toVersion(toVersion)
                .subjectKey(subjectKey)
                .changeSummary(summary)
                .changeNarrative(narrative)
                .build());
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > MAX_CHARS_PER_VERSION ? text.substring(0, MAX_CHARS_PER_VERSION) + "…" : text;
    }

    private String extractField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String key = "\"" + field + "\"";
        int idx = json.toLowerCase(Locale.ENGLISH).indexOf(key.toLowerCase(Locale.ENGLISH));
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        while (end > 0 && json.charAt(end - 1) == '\\') {
            end = json.indexOf('"', end + 1);
        }
        if (end < 0) return null;
        return json.substring(start + 1, end).replace("\\\"", "\"").replace("\\n", " ").trim();
    }
}
