package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.AiInsight;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AiInsightRepository extends JpaRepository<AiInsight, Long> {

    Page<AiInsight> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Optional<AiInsight> findTopByUserIdAndInsightTypeOrderByCreatedAtDesc(String userId, AiInsight.InsightType type);

    Optional<AiInsight> findFirstByUserIdAndInsightDateAndInsightTypeOrderByCreatedAtDesc(
            String userId, LocalDate insightDate, AiInsight.InsightType insightType);
}
