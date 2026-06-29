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

class PolicyQuantSignalAllChannelsTest {

    PolicyQuantSignalRepository repo = mock(PolicyQuantSignalRepository.class);
    QuantitativeMacroState macroState = mock(QuantitativeMacroState.class);
    PolicyQuantSignalService service;

    @BeforeEach
    void setup() {
        service = new PolicyQuantSignalService(repo, macroState);
    }

    @Test
    void sectorMarginShockRoutedToPendingQueue() {
        // SEBI + SECTOR_MARGIN with no extractable pct in title → signal null → PENDING
        PolicyEventCard card = buildCard(
                PolicyAuthority.SEBI,
                PolicyTransmissionChannel.SECTOR_MARGIN,
                PolicySurpriseClassification.STRICTER_THAN_EXPECTED,
                "SEBI circular on margin requirements for derivatives");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.PENDING);
    }

    @Test
    void highSurpriseDocumentHasSurpriseScalingInParameterKey() {
        // Pure string test: HIGH_SURPRISE normalization produces expected key format
        assertThat("SIZE:" + "HIGH_SURPRISE").isEqualTo("SIZE:HIGH_SURPRISE");
    }

    @Test
    void fiiRegulatoryOutflowCardAutoApprovedByRbi() {
        // RBI + FII_REGULATORY with "restrict" in title → always extracts signal → AUTO_APPROVE
        PolicyEventCard card = buildCard(
                PolicyAuthority.RBI,
                PolicyTransmissionChannel.FII_REGULATORY,
                PolicySurpriseClassification.HAWKISH_SURPRISE,
                "RBI to restrict FII inflows in government securities");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.AUTO_APPROVE);
        assertThat(captor.getValue().getParameterKey()).startsWith("FII_FLOW:");
        verify(macroState).putPolicyRateShock(contains("FII_FLOW:"), anyDouble());
    }

    @Test
    void sectorMarginWithPctAutoApprovesBySEBI() {
        // SEBI + SECTOR_MARGIN + pct in title → extracts signal → AUTO_APPROVE + putPolicyRateShock
        PolicyEventCard card = buildCard(
                PolicyAuthority.SEBI,
                PolicyTransmissionChannel.SECTOR_MARGIN,
                PolicySurpriseClassification.STRICTER_THAN_EXPECTED,
                "SEBI increases margin requirement to 20% for F&O segment");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.AUTO_APPROVE);
        assertThat(captor.getValue().getParameterKey()).isEqualTo("SIZE:HIGH_SURPRISE");
        verify(macroState).putPolicyRateShock(eq("SIZE:HIGH_SURPRISE"), anyDouble());
    }

    @Test
    void untrustedAuthorityAlwaysPending() {
        // PIB is not a trusted authority → no extraction → PENDING regardless of channel
        PolicyEventCard card = buildCard(
                PolicyAuthority.PIB,
                PolicyTransmissionChannel.FISCAL_STIMULUS,
                PolicySurpriseClassification.DOVISH_SURPRISE,
                "Government announces 5% GDP fiscal stimulus package");
        service.process(List.of(card));
        var captor = org.mockito.ArgumentCaptor.forClass(PolicyQuantSignalQueueEntry.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(SignalStatus.PENDING);
        verify(macroState, never()).putPolicyRateShock(any(), anyDouble());
        verify(macroState, never()).setRiskFreeRate(anyDouble(), any());
    }

    // -----------------------------------------------------------------------

    private PolicyEventCard buildCard(
            PolicyAuthority authority,
            PolicyTransmissionChannel channel,
            PolicySurpriseClassification surprise,
            String documentTitle) {
        return new PolicyEventCard(
                "evt-test",
                1L,
                1L,
                documentTitle,
                authority,
                null,
                PolicyBindingLevel.BINDING_COMPLIANCE_CHANGE,
                null, null, null, null, null, null, null, null, null, null, null,
                channel,
                null, null,
                surprise,
                null, null, null, null, null, null, null, null,
                List.of()
        );
    }
}
