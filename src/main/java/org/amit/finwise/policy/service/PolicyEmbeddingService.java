package org.amit.finwise.policy.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * pgvector-backed embeddings for policy chunks (Roadmap Phase 2.1).
 *
 * Reuses the shared {@link EmbeddingService} (same Ollama model + dimension as the
 * news pipeline) for the actual vector, and keeps policy vectors in their own
 * {@code policy_chunk_embeddings} table so they don't mingle with news embeddings.
 * Cosine similarity (pgvector {@code <=>}) drives the vector half of the hybrid
 * retriever; everything degrades to a no-op when pgvector is unavailable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyEmbeddingService {

    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;
    private final PolicyChunkRepository chunkRepository;

    private boolean ready = false;

    @PostConstruct
    public void initSchema() {
        if (!embeddingService.isAvailable()) {
            log.info("PolicyEmbeddingService disabled — pgvector/embeddings unavailable.");
            return;
        }
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS policy_chunk_embeddings (
                    chunk_id   BIGINT PRIMARY KEY,
                    embedding  vector(%d),
                    created_at TIMESTAMP DEFAULT NOW()
                )
                """.formatted(embeddingService.embeddingDimension()));
        } catch (Exception e) {
            log.warn("Could not create policy_chunk_embeddings table: {}", e.getMessage());
            return;
        }
        try {
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_policy_chunk_embedding_hnsw
                ON policy_chunk_embeddings
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
                """);
        } catch (Exception e) {
            log.debug("Policy chunk HNSW index skipped (non-critical): {}", e.getMessage());
        }
        ready = true;
        log.info("PolicyEmbeddingService ready — policy chunk embeddings enabled.");
    }

    public boolean isAvailable() {
        return ready;
    }

    /** Embed and persist the vector for one chunk (no-op when unavailable). */
    public void embedAndStore(PolicyChunk chunk) {
        if (!ready || chunk == null || chunk.getContent() == null) return;
        try {
            float[] vector = embeddingService.embedDocument(chunk.getHeading(), chunk.getContent());
            if (vector == null) return;
            store(chunk.getId(), vector);
        } catch (Exception e) {
            log.debug("Policy chunk embed failed for chunk {}: {}", chunk.getId(), e.getMessage());
        }
    }

    /** Embed and persist a batch of chunks (used on ingest and backfill). */
    public void embedAndStoreAll(List<PolicyChunk> chunks) {
        if (!ready || chunks == null) return;
        for (PolicyChunk chunk : chunks) {
            embedAndStore(chunk);
        }
    }

    /**
     * Vector similarity search over <em>current</em> policy chunks.
     *
     * @return chunk ids with cosine similarity (0..1), most similar first; empty when unavailable.
     */
    public List<ScoredChunk> searchSimilar(float[] queryEmbedding, int limit) {
        if (!ready || queryEmbedding == null) return List.of();
        String vectorStr = toVectorString(queryEmbedding);
        try {
            return jdbc.query("""
                    SELECT c.id AS chunk_id, 1 - (e.embedding <=> ?::vector) AS similarity
                    FROM policy_chunk_embeddings e
                    JOIN policy_chunks c ON c.id = e.chunk_id
                    JOIN policy_document_versions v ON c.version_id = v.id
                    WHERE v.is_current = true
                    ORDER BY e.embedding <=> ?::vector
                    LIMIT ?
                    """,
                    (rs, _) -> new ScoredChunk(rs.getLong("chunk_id"), rs.getDouble("similarity")),
                    vectorStr, vectorStr, limit);
        } catch (Exception e) {
            log.debug("Policy vector search skipped: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Backfill embeddings for current chunks that don't have one yet.
     *
     * @return number of chunks embedded.
     */
    public int backfillMissing(int batchLimit) {
        if (!ready) return 0;
        List<Long> missing;
        try {
            missing = jdbc.queryForList("""
                    SELECT c.id
                    FROM policy_chunks c
                    JOIN policy_document_versions v ON c.version_id = v.id
                    LEFT JOIN policy_chunk_embeddings e ON e.chunk_id = c.id
                    WHERE v.is_current = true AND e.chunk_id IS NULL
                    ORDER BY c.id
                    LIMIT ?
                    """, Long.class, batchLimit);
        } catch (Exception e) {
            log.warn("Policy embedding backfill query failed: {}", e.getMessage());
            return 0;
        }
        if (missing.isEmpty()) return 0;
        int count = 0;
        for (Long id : missing) {
            PolicyChunk chunk = chunkRepository.findById(id).orElse(null);
            if (chunk != null) {
                embedAndStore(chunk);
                count++;
            }
        }
        log.info("[Policy] Backfilled {} chunk embeddings", count);
        return count;
    }

    private void store(Long chunkId, float[] embedding) {
        jdbc.update("""
            INSERT INTO policy_chunk_embeddings (chunk_id, embedding)
            VALUES (?, ?::vector)
            ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding
            """, chunkId, toVectorString(embedding));
    }

    private String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(v[i]);
        }
        return sb.append("]").toString();
    }

    /** A policy chunk id with its vector similarity score. */
    public record ScoredChunk(Long chunkId, double similarity) {}
}
