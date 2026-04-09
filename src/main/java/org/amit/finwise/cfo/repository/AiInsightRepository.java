package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.AiInsight;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface AiInsightRepository extends JpaRepository<AiInsight, Long> {

    Page<AiInsight> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    Optional<AiInsight> findTopByUserIdAndInsightTypeOrderByCreatedAtDesc(String userId, AiInsight.InsightType type);

    @Query("SELECT a FROM AiInsight a WHERE a.userId = :userId AND a.insightDate = :date AND a.insightType = :type")
    Optional<AiInsight> findByUserIdAndDateAndType(@Param("userId") String userId,
                                                    @Param("date") LocalDate date,
                                                    @Param("type") AiInsight.InsightType type);
}
