package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.model.InsightClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface InsightClaimRepository extends JpaRepository<InsightClaim, Long> {

    boolean existsByInsightIdAndSymbolAndHorizon(
            Long insightId, String symbol, EventOutcome.Horizon horizon);

    /** Unscored claims old enough that their horizon may have matured. */
    @Query("SELECT c FROM InsightClaim c WHERE c.scored = false AND c.insightDate <= :before")
    List<InsightClaim> findUnscoredBefore(@Param("before") LocalDate before);

    /** Scored claims in a window — input to the calibration report. */
    @Query("SELECT c FROM InsightClaim c WHERE c.scored = true AND c.correct IS NOT NULL "
            + "AND c.insightDate >= :from ORDER BY c.provider, c.promptVersion, c.horizon")
    List<InsightClaim> findScoredSince(@Param("from") LocalDate from);
}
