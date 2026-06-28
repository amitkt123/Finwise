package org.amit.finwise.cfo.service.macro;

import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry.SignalStatus;
import org.amit.finwise.cfo.repository.macro.PolicyQuantSignalRepository;
import org.amit.finwise.policy.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PolicyQuantSignalServiceTest {

    PolicyQuantSignalRepository repo = mock(PolicyQuantSignalRepository.class);
    QuantitativeMacroState macroState = mock(QuantitativeMacroState.class);
    PolicyQuantSignalService service;

    @BeforeEach
    void setup() {
        service = new PolicyQuantSignalService(repo, macroState);
    }

    @Test
    void rbiRateChannelHighConfidenceAutoApproves() {
        // RBI + DISCOUNT_RATE channel + title with rate percentage → confidence=1.0 → AUTO_APPROVE
        PolicyEventCard card = buildCard(
                PolicyAuthority.RBI,
                PolicyBindingLevel.BINDING_COMPLIANCE_CHANGE,
                PolicyTransmissionChannel.DISCOUNT_RATE,
                PolicySurpriseClassification.HAWKISH_SURPRISE,
                "Repo rate set to 6.50%");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.AUTO_APPROVE);
        assertThat(captor.getValue().getConfidence()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void pibFiscalStimulusGoesToPending() {
        // PIB + non-rate channel + no extractable rate → saved as PENDING
        PolicyEventCard card = buildCard(
                PolicyAuthority.PIB,
                PolicyBindingLevel.INFORMATIONAL,
                PolicyTransmissionChannel.EARNINGS,
                PolicySurpriseClassification.EASIER_THAN_EXPECTED,
                "Fiscal stimulus package announced");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.PENDING);
    }

    /**
     * Build a minimal {@link PolicyEventCard} record with only the fields
     * that {@link PolicyQuantSignalService} reads, leaving others null.
     */
    private PolicyEventCard buildCard(
            PolicyAuthority authority,
            PolicyBindingLevel bindingLevel,
            PolicyTransmissionChannel channel,
            PolicySurpriseClassification surprise,
            String documentTitle) {
        return new PolicyEventCard(
                "evt-1",          // eventId
                1L,               // impactId
                1L,               // documentId
                documentTitle,    // documentTitle
                authority,
                null,             // documentType
                bindingLevel,
                null,             // policyArea
                null,             // sourceReference
                null,             // sourceUrl
                null,             // publishedDate
                null,             // effectiveFrom
                null,             // effectiveTo
                null,             // actionType
                null,             // subjectType
                null,             // subjectKey
                null,             // subjectLabel
                null,             // affectedParty
                channel,
                null,             // direction
                null,             // horizon
                surprise,
                null,             // legalForceRank
                null,             // marketMovingPower
                null,             // confidenceScore
                null,             // impactSummary
                null,             // implementationSummary
                null,             // reasoningNote
                null,             // falsificationSignal
                null,             // citation
                List.of()         // tags
        );
    }
}
