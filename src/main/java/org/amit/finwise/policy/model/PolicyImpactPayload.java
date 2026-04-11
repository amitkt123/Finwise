package org.amit.finwise.policy.model;

import org.amit.finwise.policy.service.PolicyIntelligenceService;

import java.time.LocalDate;
import java.util.List;

public record PolicyImpactPayload(
        PolicyArea policyArea,
        PolicySubjectType subjectType,
        String subjectKey,
        String subjectLabel,
        PolicyImpactDirection direction,
        PolicyImpactHorizon horizon,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Double confidenceScore,
        String impactSummary,
        String reasoningNote,
        List<String> tags
) {
    public PolicyIntelligenceService.PolicyImpactDraft toDraft() {
        return new PolicyIntelligenceService.PolicyImpactDraft(
                policyArea,
                subjectType,
                subjectKey,
                subjectLabel,
                direction,
                horizon,
                effectiveFrom,
                effectiveTo,
                confidenceScore,
                impactSummary,
                reasoningNote,
                tags
        );
    }
}
