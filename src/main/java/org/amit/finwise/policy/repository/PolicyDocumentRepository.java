package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyAuthority;
import org.amit.finwise.policy.model.PolicyDocument;
import org.amit.finwise.policy.model.PolicyDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {

    Optional<PolicyDocument> findByDocumentKey(String documentKey);

    Optional<PolicyDocument> findBySourceReferenceIgnoreCase(String sourceReference);

    Optional<PolicyDocument> findByExternalDocumentIdIgnoreCase(String externalDocumentId);

    Optional<PolicyDocument> findBySourceUrlIgnoreCase(String sourceUrl);

    List<PolicyDocument> findTop20ByOrderByUpdatedAtDesc();

    @Query("""
            SELECT d FROM PolicyDocument d
            WHERE (:authority IS NULL OR d.authority = :authority)
              AND (:status IS NULL OR d.status = :status)
            ORDER BY d.updatedAt DESC
            """)
    List<PolicyDocument> findRecentDocuments(
            @Param("authority") PolicyAuthority authority,
            @Param("status") PolicyDocumentStatus status);

    @NativeQuery("""
            WITH q AS (SELECT websearch_to_tsquery('simple', :query) AS query)
            SELECT d.*
            FROM policy_documents d
            CROSS JOIN q
            WHERE (
                    setweight(to_tsvector('simple', coalesce(d.title, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(d.summary, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(d.tags_csv, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(d.affected_sectors_csv, '')), 'A')
            ) @@ q.query
            ORDER BY ts_rank_cd(
                    setweight(to_tsvector('simple', coalesce(d.title, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(d.summary, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(d.tags_csv, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(d.affected_sectors_csv, '')), 'A'),
                 q.query
            ) DESC, d.updated_at DESC
            """)
    List<PolicyDocument> search(@Param("query") String query);
}
