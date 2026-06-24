package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.policy.model.PolicyChange;
import org.amit.finwise.policy.repository.PolicyChangeRepository;
import org.amit.finwise.policy.repository.PolicyFalsificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 3.1 — Policy topic timeline.
 *
 * Groups {@link PolicyChange} records by subjectKey into an ordered evolution view.
 * The endpoint {@code GET /api/policy-intelligence/timeline/{subjectKey}} exposes this.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyTimelineService {

    private final PolicyChangeRepository changeRepository;
    private final PolicyFalsificationRepository falsificationRepository;

    @Transactional(readOnly = true)
    public PolicyTimeline getTimeline(String subjectKey) {
        List<PolicyChange> changes = changeRepository.findBySubjectKeyOrderByCreatedAtDesc(subjectKey);
        Double hitRate = falsificationRepository.computeHitRateForSubject(subjectKey);

        List<TimelineEntry> entries = changes.stream()
                .map(c -> new TimelineEntry(
                        c.getId(),
                        c.getDocument().getId(),
                        c.getDocument().getDocumentKey(),
                        c.getDocument().getTitle(),
                        c.getDocument().getAuthority() != null ? c.getDocument().getAuthority().name() : null,
                        c.getFromVersion() != null ? c.getFromVersion().getVersionNumber() : null,
                        c.getToVersion().getVersionNumber(),
                        c.getToVersion().getIssuedAt(),
                        c.getToVersion().getEffectiveFrom() != null ? c.getToVersion().getEffectiveFrom().toString() : null,
                        c.getChangeSummary(),
                        c.getChangeNarrative(),
                        c.getCreatedAt()
                ))
                .toList();

        return new PolicyTimeline(subjectKey, entries, hitRate);
    }

    @Transactional(readOnly = true)
    public List<String> listTrackedSubjects() {
        return changeRepository.findDistinctSubjectKeys();
    }

    public record PolicyTimeline(
            String subjectKey,
            List<TimelineEntry> entries,
            Double historicalHitRate
    ) {}

    public record TimelineEntry(
            Long changeId,
            Long documentId,
            String documentKey,
            String documentTitle,
            String authority,
            Integer fromVersionNumber,
            Integer toVersionNumber,
            LocalDateTime issuedAt,
            String effectiveFrom,
            String changeSummary,
            String changeNarrative,
            LocalDateTime recordedAt
    ) {}
}
