package org.amit.finwise.cfo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.ArticleEntity;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.repository.ArticleEntityRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;

/**
 * Writes typed article→entity edges (DF-6) — the knowledge graph, in Postgres.
 *
 * Two sources feed it: the gazetteer/Tier-2 extraction at ingest (high precision)
 * and the LLM refinement (gated at confidence >= 0.6). Edges are de-duplicated by
 * (article, type, entity, source), so re-runs are idempotent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleEntityService {

    /** Gate for trusting LLM-asserted edges — mirrors the refinement merge gate. */
    private static final double LLM_CONFIDENCE_GATE = 0.6;

    private final ArticleEntityRepository repo;

    /** Edges from the deterministic gazetteer pass: comma-separated symbols + sectors. */
    public void writeGazetteerEdges(NewsArticle article) {
        if (article.getId() == null) return;
        writeEdges(article.getId(), split(article.getRelatedSymbols()),
                ArticleEntity.EntityType.INSTRUMENT, 0.9, ArticleEntity.Source.GAZETTEER);
        writeEdges(article.getId(), split(article.getRelatedSectors()),
                ArticleEntity.EntityType.SECTOR, 0.8, ArticleEntity.Source.GAZETTEER);
    }

    /** Edges asserted by the LLM during refinement, gated by its own confidence. */
    public void writeLlmEdges(Long articleId, Collection<String> symbols,
                              Collection<String> sectors, double confidence) {
        if (articleId == null || confidence < LLM_CONFIDENCE_GATE) return;
        writeEdges(articleId, symbols, ArticleEntity.EntityType.INSTRUMENT,
                confidence, ArticleEntity.Source.LLM);
        writeEdges(articleId, sectors, ArticleEntity.EntityType.SECTOR,
                confidence, ArticleEntity.Source.LLM);
    }

    private void writeEdges(Long articleId, Collection<String> entityIds,
                            ArticleEntity.EntityType type, double confidence,
                            ArticleEntity.Source source) {
        if (entityIds == null) return;
        for (String raw : entityIds) {
            if (raw == null) continue;
            String entityId = raw.trim();
            if (entityId.isEmpty() || entityId.length() > 100) continue;
            if (repo.existsByArticleIdAndEntityTypeAndEntityIdAndSource(
                    articleId, type, entityId, source)) {
                continue;
            }
            try {
                repo.save(ArticleEntity.builder()
                        .articleId(articleId)
                        .entityType(type)
                        .entityId(entityId)
                        .confidence(confidence)
                        .source(source)
                        .build());
            } catch (RuntimeException _) {
                // Concurrent insert raced the unique constraint — benign.
                log.debug("Skipped duplicate entity edge {}:{} for article {}",
                        type, entityId, articleId);
            }
        }
    }

    private static Collection<String> split(String csv) {
        if (csv == null || csv.isBlank()) return java.util.List.of();
        return Arrays.stream(csv.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }
}
