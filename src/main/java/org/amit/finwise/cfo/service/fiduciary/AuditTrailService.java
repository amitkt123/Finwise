package org.amit.finwise.cfo.service.fiduciary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.RecommendationAudit;
import org.amit.finwise.cfo.repository.RecommendationAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTrailService {

    private final RecommendationAuditRepository repo;
    private final ConflictDisclosureConfig config;
    private final ObjectMapper objectMapper;

    public RecommendationAudit record(
        String userId, String type, String symbol,
        String rationale, Double confidence, List<String> dataSources
    ) {
        String sourcesJson;
        try { sourcesJson = objectMapper.writeValueAsString(dataSources); }
        catch (JsonProcessingException e) { sourcesJson = "[]"; }

        RecommendationAudit audit = RecommendationAudit.builder()
            .userId(userId)
            .type(type)
            .symbol(symbol)
            .rationale(rationale)
            .confidence(confidence)
            .conflictState(config.getConflictStatement())
            .dataSourcesJson(sourcesJson)
            .engineVersion(config.getEngineVersion())
            .build();

        return repo.save(audit);
    }

    public List<RecommendationAudit> findByUser(String userId, LocalDateTime from) {
        return repo.findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc(userId, from);
    }

    public void recordOutcome(UUID auditId, String outcome) {
        repo.findById(auditId).ifPresent(a -> {
            a.setOutcome(outcome);
            a.setOutcomeAt(Instant.now());
            repo.save(a);
        });
    }

    public void acknowledge(UUID auditId) {
        repo.findById(auditId).ifPresent(a -> {
            a.setUserAcked(true);
            repo.save(a);
        });
    }
}
