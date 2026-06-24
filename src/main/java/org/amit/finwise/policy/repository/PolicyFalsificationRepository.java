package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyFalsificationRecord;
import org.amit.finwise.policy.model.PolicyImpact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface PolicyFalsificationRepository extends JpaRepository<PolicyFalsificationRecord, Long> {

    boolean existsByImpactId(Long impactId);

    List<PolicyFalsificationRecord> findBySubjectKeyOrderByCheckedAtDesc(String subjectKey);

    /** Impacts past their effective date that have no falsification record yet. */
    @Query("""
        SELECT i FROM PolicyImpact i
        WHERE i.effectiveFrom IS NOT NULL
          AND i.effectiveFrom <= :cutoff
          AND i.direction IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM PolicyFalsificationRecord f WHERE f.impact.id = i.id
          )
        """)
    List<PolicyImpact> findImpactsPendingFalsification(@Param("cutoff") LocalDate cutoff);

    @Query("SELECT AVG(CASE WHEN f.directionConfirmed = true THEN 1.0 ELSE 0.0 END) FROM PolicyFalsificationRecord f WHERE f.subjectKey = :subjectKey")
    Double computeHitRateForSubject(@Param("subjectKey") String subjectKey);
}
