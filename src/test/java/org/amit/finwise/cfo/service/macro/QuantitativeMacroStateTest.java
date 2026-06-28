package org.amit.finwise.cfo.service.macro;

import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.repository.macro.MacroStateAuditRepository;
import org.amit.finwise.cfo.repository.macro.MacroStateSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class QuantitativeMacroStateTest {
    MacroStateSnapshotRepository snapRepo = mock(MacroStateSnapshotRepository.class);
    MacroStateAuditRepository auditRepo = mock(MacroStateAuditRepository.class);
    RiskProperties riskProps = mock(RiskProperties.class);
    QuantitativeMacroState state;

    @BeforeEach void setup() {
        when(riskProps.getRiskFreeRate()).thenReturn(0.065);
        when(snapRepo.findById("riskFreeRate")).thenReturn(Optional.empty());
        state = new QuantitativeMacroState(snapRepo, auditRepo, riskProps);
    }

    @Test void fallsBackToRiskPropertiesWhenNoSnapshot() {
        assertThat(state.getRiskFreeRate()).isEqualTo(0.065);
    }

    @Test void setRiskFreeRatePersistsAndAudits() {
        state.setRiskFreeRate(0.068, "FBIL");
        assertThat(state.getRiskFreeRate()).isEqualTo(0.068);
        verify(snapRepo).save(any());
        verify(auditRepo).save(any());
    }

    @Test void policyRateShocksEmptyByDefault() {
        assertThat(state.getPolicyRateShocks()).isEmpty();
    }
}
