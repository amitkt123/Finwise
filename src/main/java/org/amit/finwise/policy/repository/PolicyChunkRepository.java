package org.amit.finwise.policy.repository;

import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.model.PolicyDocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, Long> {

    List<PolicyChunk> findByVersionOrderByChunkIndexAsc(PolicyDocumentVersion version);

    @Modifying
    @Query("DELETE FROM PolicyChunk c WHERE c.version = :version")
    void deleteByVersion(@Param("version") PolicyDocumentVersion version);

    @NativeQuery("""
            WITH q AS (SELECT websearch_to_tsquery('simple', :query) AS query)
            SELECT c.*
            FROM policy_chunks c
            JOIN policy_document_versions v ON c.version_id = v.id
            JOIN policy_documents d ON v.document_id = d.id
            CROSS JOIN q
            WHERE v.is_current = true
              AND (
                    setweight(to_tsvector('simple', coalesce(c.heading, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(c.section_path, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(c.keyword_text, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(c.content, '')), 'C')
              ) @@ q.query
            ORDER BY ts_rank_cd(
                    setweight(to_tsvector('simple', coalesce(c.heading, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(c.section_path, '')), 'B')
                 || setweight(to_tsvector('simple', coalesce(c.keyword_text, '')), 'A')
                 || setweight(to_tsvector('simple', coalesce(c.content, '')), 'C'),
                 q.query
            ) DESC, d.updated_at DESC, c.chunk_index ASC
            """)
    List<PolicyChunk> searchCurrentChunks(@Param("query") String query);
}
