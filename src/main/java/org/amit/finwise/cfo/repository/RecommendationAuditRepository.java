package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.RecommendationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface RecommendationAuditRepository extends JpaRepository<RecommendationAudit, UUID> {
    List<RecommendationAudit> findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc(
        String userId, LocalDateTime after);
    List<RecommendationAudit> findByUserIdAndOutcomeIsNullOrderByGeneratedAtAsc(String userId);
}
