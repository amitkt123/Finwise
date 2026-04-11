package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyImpact;
import org.amit.finwise.policy.model.PolicySubjectType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PolicyImpactRepository extends JpaRepository<PolicyImpact, Long> {

    void deleteByVersion(org.amit.finwise.policy.model.PolicyDocumentVersion version);

    @Query("""
            SELECT i FROM PolicyImpact i
            WHERE i.subjectType = :subjectType
              AND i.subjectKey IN :subjectKeys
              AND (i.effectiveFrom IS NULL OR i.effectiveFrom <= :asOfDate)
              AND (i.effectiveTo IS NULL OR i.effectiveTo >= :asOfDate)
            ORDER BY coalesce(i.confidenceScore, 0.0) DESC, i.createdAt DESC
            """)
    List<PolicyImpact> findActiveImpactsForSubjects(
            @Param("subjectType") PolicySubjectType subjectType,
            @Param("subjectKeys") Collection<String> subjectKeys,
            @Param("asOfDate") LocalDate asOfDate);

    @Query("""
            SELECT i FROM PolicyImpact i
            WHERE (i.effectiveFrom IS NULL OR i.effectiveFrom <= :asOfDate)
              AND (i.effectiveTo IS NULL OR i.effectiveTo >= :asOfDate)
              AND (
                    lower(i.impactSummary) LIKE lower(concat('%', :query, '%'))
                 OR lower(coalesce(i.reasoningNote, '')) LIKE lower(concat('%', :query, '%'))
                 OR lower(i.subjectKey) LIKE lower(concat('%', :query, '%'))
                 OR lower(i.subjectLabel) LIKE lower(concat('%', :query, '%'))
                 OR lower(coalesce(i.tagsCsv, '')) LIKE lower(concat('%', :query, '%'))
              )
            ORDER BY coalesce(i.confidenceScore, 0.0) DESC, i.createdAt DESC
            """)
    List<PolicyImpact> searchActiveImpacts(
            @Param("query") String query,
            @Param("asOfDate") LocalDate asOfDate);
}
