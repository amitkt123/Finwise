package org.amit.expensetracker.cfo.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

/**
 * Generates and stores article embeddings using Ollama's nomic-embed-text model,
 * and provides similarity search (RAG) for the LLM refinement prompt.
 *
 * Architecture:
 *   - Embeddings are generated ASYNCHRONOUSLY after each article is saved.
 *     The fetch cycle does not wait for them.
 *   - nomic-embed-text produces 768-dimensional vectors.
 *   - Stored in a separate table `cfo_news_embeddings` using pgvector.
 *   - Similarity search uses cosine distance (<=> operator) with HNSW index.
 *   - Only articles with high confidence OR LLM-reviewed are used as RAG context
 *     (avoids recycling bad classifications into future analysis).
 *
 * RAG value:
 *   Without RAG: LLM gives generic analysis ("crude spike is bad for FMCG")
 *   With RAG:    LLM reasons from historical evidence
 *                ("Last time crude spiked during Middle East conflict [Oct 2023],
 *                  HINDUNILVR margins compressed ~180bps in Q3 results")
 *
 * Cold start: First few days the corpus is small. Value compounds over weeks.
 */
@Slf4j
@Service
public class EmbeddingService {

    /** nomic-embed-text produces 768-dim vectors */
    private static final int EMBEDDING_DIM = 768;

    /**
     * nomic-embed-text uses task prefixes for better retrieval quality.
     * Documents use "search_document:", queries use "search_query:".
     */
    private static final String DOC_PREFIX   = "search_document: ";
    private static final String QUERY_PREFIX = "search_query: ";

    /** Max chars to embed — keeps embedding fast; titles+summary rarely exceed this */
    private static final int MAX_EMBED_CHARS = 600;

    private final JdbcTemplate jdbc;
    private final RestClient restClient;

    @Value("${cfo.llm.ollama.embedding-model:nomic-embed-text}")
    private String embeddingModel;

    public EmbeddingService(
            JdbcTemplate jdbc,
            @Value("${cfo.llm.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.jdbc = jdbc;
        this.restClient = RestClient.builder()
                .baseUrl(ollamaBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    // ── Schema init ───────────────────────────────────────────────────────────

    /** Whether pgvector is available — set during init, checked before every DB operation. */
    private boolean pgvectorAvailable = false;

    @PostConstruct
    public void initSchema() {
        // Step 1: enable extension (needs superuser; swallow if missing)
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        } catch (Exception e) {
            log.warn("pgvector extension not available — run as superuser: CREATE EXTENSION vector; ({})",
                    e.getMessage());
            log.warn("Embedding / RAG features disabled until pgvector is installed.");
            return;  // don't try to create the table if the type doesn't exist
        }

        // Step 2: create embeddings table (only if vector type is available)
        try {
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS cfo_news_embeddings (
                    article_id  BIGINT PRIMARY KEY,
                    embedding   vector(%d),
                    created_at  TIMESTAMP DEFAULT NOW()
                )
                """.formatted(EMBEDDING_DIM));
        } catch (Exception e) {
            log.warn("Could not create cfo_news_embeddings table: {}", e.getMessage());
            return;
        }

        // Step 3: HNSW index (non-critical — falls back to sequential scan)
        try {
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_cfo_news_embedding_hnsw
                ON cfo_news_embeddings
                USING hnsw (embedding vector_cosine_ops)
                WITH (m = 16, ef_construction = 64)
                """);
        } catch (Exception e) {
            log.debug("HNSW index skipped (non-critical): {}", e.getMessage());
        }

        pgvectorAvailable = true;
        log.info("EmbeddingService ready — pgvector enabled (model={}, dim={})", embeddingModel, EMBEDDING_DIM);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generate and store embedding for a newly saved article.
     * Called asynchronously — does NOT block the fetch cycle.
     */
    @Async
    public void generateAndStore(Long articleId, String title, String summary) {
        if (!pgvectorAvailable) return;
        String text = DOC_PREFIX + title
                + (summary != null && !summary.isBlank() ? ". " + summary : "");
        if (text.length() > MAX_EMBED_CHARS) text = text.substring(0, MAX_EMBED_CHARS);

        try {
            float[] embedding = callEmbeddingApi(text);
            store(articleId, embedding);
            log.debug("Embedding stored for article {}", articleId);
        } catch (Exception e) {
            log.warn("Embedding failed for article {}: {}", articleId, e.getMessage());
        }
    }

    /**
     * Find articles similar to the given text (for RAG context injection).
     *
     * Filters:
     *   - Only articles with tier1_confidence >= 0.6 OR already llm_reviewed
     *     (prevents recycling low-quality classifications as context)
     *   - Only last 90 days (recent market context is more relevant)
     *   - Excludes the current article itself
     *
     * Returns at most `limit` results, ordered by cosine similarity.
     */
    public List<SimilarArticle> findSimilar(String title, String summary,
                                             Long excludeArticleId, int limit) {
        if (!pgvectorAvailable) return List.of();
        String queryText = QUERY_PREFIX + title;
        if (queryText.length() > MAX_EMBED_CHARS) queryText = queryText.substring(0, MAX_EMBED_CHARS);

        try {
            float[] queryEmbedding = callEmbeddingApi(queryText);
            String vectorStr = toVectorString(queryEmbedding);

            return jdbc.query("""
                SELECT
                    a.id,
                    a.title,
                    a.sentiment,
                    a.category,
                    a.sector_impact,
                    a.llm_impact_note,
                    a.related_symbols,
                    a.published_date,
                    1 - (e.embedding <=> ?::vector) AS similarity
                FROM cfo_news_articles a
                JOIN cfo_news_embeddings e ON a.id = e.article_id
                WHERE a.id != ?
                  AND a.published_date >= CURRENT_DATE - INTERVAL '90 days'
                  AND (a.llm_reviewed = true OR a.tier1_confidence >= 0.6)
                ORDER BY e.embedding <=> ?::vector
                LIMIT ?
                """,
                (rs, rowNum) -> new SimilarArticle(
                        rs.getLong("id"),
                        rs.getString("title"),
                        rs.getString("sentiment"),
                        rs.getString("category"),
                        rs.getString("sector_impact"),
                        rs.getString("llm_impact_note"),
                        rs.getString("related_symbols"),
                        rs.getDate("published_date") != null
                                ? rs.getDate("published_date").toLocalDate() : null,
                        rs.getDouble("similarity")
                ),
                vectorStr, excludeArticleId, vectorStr, limit);

        } catch (Exception e) {
            log.debug("Similarity search skipped (corpus may be too small): {}", e.getMessage());
            return List.of();
        }
    }

    // ── Ollama embedding API ──────────────────────────────────────────────────

    private float[] callEmbeddingApi(String text) {
        EmbeddingRequest req = new EmbeddingRequest(embeddingModel, text);
        EmbeddingResponse resp = restClient.post()
                .uri("/api/embeddings")
                .body(req)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (resp == null || resp.embedding() == null || resp.embedding().isEmpty()) {
            throw new RuntimeException("Empty embedding response from Ollama");
        }
        float[] vector = new float[resp.embedding().size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = resp.embedding().get(i).floatValue();
        }
        return vector;
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private void store(Long articleId, float[] embedding) {
        jdbc.update("""
            INSERT INTO cfo_news_embeddings (article_id, embedding)
            VALUES (?, ?::vector)
            ON CONFLICT (article_id) DO UPDATE SET embedding = EXCLUDED.embedding
            """, articleId, toVectorString(embedding));
    }

    private String toVectorString(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(v[i]);
        }
        return sb.append("]").toString();
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    record EmbeddingRequest(String model, String prompt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(@JsonProperty("embedding") List<Double> embedding) {}

    /**
     * A similar historical article returned by findSimilar().
     * Used to inject grounded context into the LLM prompt.
     */
    public record SimilarArticle(
            Long      id,
            String    title,
            String    sentiment,
            String    category,
            String    sectorImpact,
            String    impactNote,
            String    relatedSymbols,
            LocalDate publishedDate,
            double    similarity         // 0-1, higher = more similar
    ) {}
}
