package org.amit.finwise.cfo.service.fiduciary;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.cfo.model.RecommendationAudit;
import org.amit.finwise.cfo.repository.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditTrailServiceTest {

    @Mock RecommendationAuditRepository repo;
    @Mock ConflictDisclosureConfig config;
    // Not @Mock in the original brief — added because AuditTrailService's constructor
    // (via @RequiredArgsConstructor) requires an ObjectMapper; without a mock here,
    // Mockito's @InjectMocks constructor injection passes null and record() NPEs on
    // objectMapper.writeValueAsString(...). A real ObjectMapper works fine as a mock target.
    @Mock ObjectMapper objectMapper;
    @InjectMocks AuditTrailService svc;

    @Test
    void record_savesAuditEntry() {
        when(config.getConflictStatement()).thenReturn("NONE");
        when(config.getEngineVersion()).thenReturn("v2");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        RecommendationAudit audit = svc.record("u1", "REBALANCE", null,
            "Concentration too high", 0.72, List.of("NSE", "AMFI"));

        ArgumentCaptor<RecommendationAudit> cap = ArgumentCaptor.forClass(RecommendationAudit.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo("u1");
        assertThat(cap.getValue().getType()).isEqualTo("REBALANCE");
        assertThat(cap.getValue().getConfidence()).isEqualTo(0.72);
        assertThat(cap.getValue().getConflictState()).isEqualTo("NONE");
    }

    @Test
    void findByUser_delegatesToRepo() {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        when(repo.findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc("u1", from))
            .thenReturn(List.of());
        assertThat(svc.findByUser("u1", from)).isEmpty();
    }
}
