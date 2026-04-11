package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.model.PolicyDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, Long> {

    List<PolicyChunk> findByVersionOrderByChunkIndexAsc(PolicyDocumentVersion version);

    @Modifying
    @Query("DELETE FROM PolicyChunk c WHERE c.version = :version")
    void deleteByVersion(@Param("version") PolicyDocumentVersion version);

    @Query("""
            SELECT c FROM PolicyChunk c
            JOIN c.version v
            JOIN v.document d
            WHERE v.current = true
              AND (
                    lower(c.content) LIKE lower(concat('%', :query, '%'))
                 OR lower(coalesce(c.heading, '')) LIKE lower(concat('%', :query, '%'))
                 OR lower(coalesce(c.sectionPath, '')) LIKE lower(concat('%', :query, '%'))
                 OR lower(coalesce(c.keywordText, '')) LIKE lower(concat('%', :query, '%'))
              )
            ORDER BY d.updatedAt DESC, c.chunkIndex ASC
            """)
    List<PolicyChunk> searchCurrentChunks(@Param("query") String query);
}
