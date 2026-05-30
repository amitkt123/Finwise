package org.amit.finwise.policy.model;

import org.amit.finwise.policy.service.PolicyIntelligenceService;

import java.time.LocalDate;
import java.util.List;

public record PolicyImpactPayload(
        PolicyArea policyArea,
        PolicySubjectType subjectType,
        String subjectKey,
        String subjectLabel,
        PolicyActionType actionType,
        PolicyTransmissionChannel transmissionChannel,
        String affectedParty,
        PolicyImpactDirection direction,
        PolicyImpactHorizon horizon,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String implementationSummary,
        PolicySurpriseClassification surpriseClassification,
        Integer legalForceRank,
        Integer marketMovingPower,
        Double confidenceScore,
        String impactSummary,
        String reasoningNote,
        String falsificationSignal,
        List<String> tags
) {
    public PolicyIntelligenceService.PolicyImpactDraft toDraft() {
        return new PolicyIntelligenceService.PolicyImpactDraft(
                policyArea,
                subjectType,
                subjectKey,
                subjectLabel,
                actionType,
                transmissionChannel,
                affectedParty,
                direction,
                horizon,
                effectiveFrom,
                effectiveTo,
                implementationSummary,
                surpriseClassification,
                legalForceRank,
                marketMovingPower,
                confidenceScore,
                impactSummary,
                reasoningNote,
                falsificationSignal,
                tags
        );
    }
}
