package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyAuthority;
import org.amit.finwise.policy.model.PolicyDocument;
import org.amit.finwise.policy.model.PolicyDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("""
            SELECT d FROM PolicyDocument d
            WHERE lower(d.title) LIKE lower(concat('%', :query, '%'))
               OR lower(coalesce(d.summary, '')) LIKE lower(concat('%', :query, '%'))
               OR lower(coalesce(d.tagsCsv, '')) LIKE lower(concat('%', :query, '%'))
               OR lower(coalesce(d.affectedSectorsCsv, '')) LIKE lower(concat('%', :query, '%'))
            ORDER BY d.updatedAt DESC
            """)
    List<PolicyDocument> search(@Param("query") String query);
}
