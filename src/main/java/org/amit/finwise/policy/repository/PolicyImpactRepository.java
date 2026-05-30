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

    @Query(value = """
            WITH q AS (SELECT websearch_to_tsquery('simple', :query) AS query)
            SELECT i.*
            FROM policy_impacts i
            CROSS JOIN q
            WHERE (i.effective_from IS NULL OR i.effective_from <= :asOfDate)
              AND (i.effective_to IS NULL OR i.effective_to >= :asOfDate)
              AND (
                    setweight(to_tsvector('simple', coalesce(i.subject_key, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.subject_label, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.tags_csv, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.impact_summary, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(i.reasoning_note, '')), 'C')
                 || setweight(to_tsvector('simple', coalesce(i.affected_party, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(i.implementation_summary, '')), 'C')
                 || setweight(to_tsvector('simple', coalesce(i.falsification_signal, '')), 'D')
              ) @@ q.query
            ORDER BY ts_rank_cd(
                    setweight(to_tsvector('simple', coalesce(i.subject_key, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.subject_label, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.tags_csv, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(i.impact_summary, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(i.reasoning_note, '')), 'C')
                 || setweight(to_tsvector('simple', coalesce(i.affected_party, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(i.implementation_summary, '')), 'C')
                 || setweight(to_tsvector('simple', coalesce(i.falsification_signal, '')), 'D'),
                 q.query
            ) DESC, coalesce(i.confidence_score, 0.0) DESC, i.created_at DESC
            """, nativeQuery = true)
    List<PolicyImpact> searchActiveImpacts(
            @Param("query") String query,
            @Param("asOfDate") LocalDate asOfDate);
}
