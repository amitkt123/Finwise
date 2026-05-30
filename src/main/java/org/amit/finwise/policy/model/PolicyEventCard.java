package org.amit.finwise.policy.model;

import java.time.LocalDate;
import java.util.List;

public record PolicyEventCard(
        String eventId,
        Long impactId,
        Long documentId,
        String documentTitle,
        PolicyAuthority authority,
        PolicyDocumentType documentType,
        PolicyBindingLevel bindingLevel,
        PolicyArea policyArea,
        String sourceReference,
        String sourceUrl,
        LocalDate publishedDate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        PolicyActionType actionType,
        PolicySubjectType subjectType,
        String subjectKey,
        String subjectLabel,
        String affectedParty,
        PolicyTransmissionChannel transmissionChannel,
        PolicyImpactDirection direction,
        PolicyImpactHorizon horizon,
        PolicySurpriseClassification surpriseClassification,
        Integer legalForceRank,
        Integer marketMovingPower,
        Double confidenceScore,
        String impactSummary,
        String implementationSummary,
        String reasoningNote,
        String falsificationSignal,
        PolicyCitation citation,
        List<String> tags
) {
    public record PolicyCitation(
            PolicyAuthority authority,
            String reference,
            String title,
            String url,
            LocalDate publishedDate,
            LocalDate effectiveFrom
    ) {
    }
}
