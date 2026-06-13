package org.amit.finwise.cfo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.NewsCluster;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.NewsClusterRepository;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Ingest-time event clustering with embedding dedup (DF-6).
 *
 * For each freshly-saved article it: embeds the text synchronously, searches the
 * last 72h for a near-duplicate (cosine >= {@code dedup-threshold}), and either
 * attaches the article to that match's cluster (marking it a duplicate so it is
 * skipped by LLM refinement) or opens a new cluster. Then it persists the
 * embedding and writes the gazetteer knowledge-graph edges.
 *
 * Unlike the old fire-and-forget async embed, this runs in the fetch loop because
 * the cluster decision needs the vector NOW — but it is deliberately not wrapped
 * in the fetch transaction, so the Ollama HTTP call never holds a DB connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsClusteringService {

    private final EmbeddingService embeddingService;
    private final NewsClusterRepository clusterRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final ArticleEntityService articleEntityService;

    @Value("${cfo.rag.dedup-threshold:0.92}")
    private double dedupThreshold;

    @Value("${cfo.rag.dedup-window-hours:72}")
    private int dedupWindowHours;

    /**
     * Assign a saved article to a cluster (existing or new), persist its
     * embedding, and write gazetteer entity edges. Best-effort: any failure is
     * logged and swallowed so a single bad article never breaks the fetch cycle.
     */
    public void cluster(NewsArticle article) {
        if (article == null || article.getId() == null) return;
        try {
            float[] embedding =
                    embeddingService.embedDocument(article.getTitle(), article.getSummary());

            Optional<EmbeddingService.RecentMatch> match = embedding == null
                    ? Optional.empty()
                    : embeddingService.findMostSimilarWithin(
                            embedding, dedupWindowHours, dedupThreshold);

            if (match.isPresent() && match.get().clusterId() != null) {
                attachToExisting(article, match.get());
            } else {
                openNewCluster(article);
            }

            // Persist the embedding AFTER the dedup search so it can't self-match.
            embeddingService.storeEmbedding(article.getId(), embedding);
            articleEntityService.writeGazetteerEdges(article);

        } catch (RuntimeException e) {
            log.warn("Clustering failed for article {} ('{}'): {}",
                    article.getId(), truncate(article.getTitle()), e.getMessage());
        }
    }

    private void attachToExisting(NewsArticle article, EmbeddingService.RecentMatch match) {
        clusterRepository.findById(match.clusterId()).ifPresent(cluster -> {
            cluster.setLastSeen(LocalDateTime.now());
            cluster.setArticleCount(
                    (cluster.getArticleCount() == null ? 1 : cluster.getArticleCount()) + 1);
            clusterRepository.save(cluster);
        });
        article.setClusterId(match.clusterId());
        article.setClusterDuplicate(true);
        newsArticleRepository.save(article);
        log.debug("Article {} deduped into cluster {} (sim={})",
                article.getId(), match.clusterId(), String.format("%.3f", match.similarity()));
    }

    private void openNewCluster(NewsArticle article) {
        LocalDateTime firstSeen = article.getPublishedAt() != null
                ? article.getPublishedAt() : LocalDateTime.now();
        NewsCluster cluster = clusterRepository.save(NewsCluster.builder()
                .canonicalArticleId(article.getId())
                .title(article.getTitle())
                .firstSeen(firstSeen)
                .lastSeen(firstSeen)
                .articleCount(1)
                .build());
        article.setClusterId(cluster.getId());
        article.setClusterDuplicate(false);
        newsArticleRepository.save(article);
    }

    private static String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
