package org.amit.finwise.policy.model;

import org.amit.finwise.policy.service.PolicyIntelligenceService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PolicyIngestionPayload(
        String documentKey,
        PolicyAuthority authority,
        PolicyDocumentType documentType,
        PolicyBindingLevel bindingLevel,
        PolicyArea policyArea,
        PolicyDocumentStatus status,
        String title,
        String sourceUrl,
        String sourceReference,
        String externalDocumentId,
        LocalDate publishedDate,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        LocalDateTime issuedAt,
        String versionLabel,
        String supersedesReference,
        String summary,
        List<String> tags,
        List<String> affectedSectors,
        String metadataJson,
        String rawText,
        List<PolicyImpactPayload> impacts
) {
    public PolicyIntelligenceService.PolicyIngestionRequest toRequest() {
        return new PolicyIntelligenceService.PolicyIngestionRequest(
                documentKey,
                authority,
                documentType,
                bindingLevel,
                policyArea,
                status,
                title,
                sourceUrl,
                sourceReference,
                externalDocumentId,
                publishedDate,
                effectiveFrom,
                effectiveTo,
                issuedAt,
                versionLabel,
                supersedesReference,
                summary,
                tags,
                affectedSectors,
                metadataJson,
                rawText,
                impacts != null ? impacts.stream().map(PolicyImpactPayload::toDraft).toList() : null
        );
    }
}
