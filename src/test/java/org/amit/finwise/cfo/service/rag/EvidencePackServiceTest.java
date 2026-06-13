package org.amit.finwise.cfo.service.rag;

import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.repository.ArticleEntityRepository;
import org.amit.finwise.cfo.repository.EventOutcomeRepository;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Evidence-pack ranking (DF-6): the hybrid score (similarity × recency × overlap)
 * must lift a recent, portfolio-overlapping event above a stale one that is more
 * cosine-similar, and outcomes must render as readable excess-return lines.
 */
class EvidencePackServiceTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final ArticleEntityRepository entityRepo = mock(ArticleEntityRepository.class);
    private final EventOutcomeRepository outcomeRepo = mock(EventOutcomeRepository.class);

    private final EvidencePackService service =
            new EvidencePackService(embeddingService, entityRepo, outcomeRepo);

    @Test
    void recentPortfolioOverlapBeatsStaleHigherSimilarity() {
        var recent = new EmbeddingService.ClusterMatch(
                1L, "TCS Q3 beats", LocalDateTime.now().minusDays(2), 0.70);
        var stale = new EmbeddingService.ClusterMatch(
                2L, "Old INFY note", LocalDateTime.now().minusDays(220), 0.90);
        when(embeddingService.findSimilarClusters(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(recent, stale));

        when(entityRepo.findInstrumentSymbolsForCluster(1L)).thenReturn(List.of("TCS"));
        when(entityRepo.findInstrumentSymbolsForCluster(2L)).thenReturn(List.of("INFY"));

        when(outcomeRepo.findByClusterId(1L)).thenReturn(List.of(
                outcome(1L, "TCS", EventOutcome.Horizon.H5D, 0.032)));
        when(outcomeRepo.findByClusterId(2L)).thenReturn(List.of());

        List<EvidencePackService.EvidencePack> packs =
                service.retrieve("TCS earnings", Set.of("TCS"), 5);

        assertEquals(2, packs.size());
        assertEquals(1L, packs.get(0).clusterId());                 // recent + overlap wins
        assertTrue(packs.get(0).score() > packs.get(1).score());
        assertTrue(packs.get(0).outcomeSummary().contains("TCS +3.2%"));
        assertTrue(packs.get(0).outcomeSummary().contains("over 5d"));
        assertTrue(packs.get(0).render().contains("→"));
    }

    @Test
    void emptyQueryReturnsNothing() {
        assertTrue(service.retrieve("  ", Set.of("TCS"), 5).isEmpty());
        verifyNoInteractions(embeddingService);
    }

    private EventOutcome outcome(Long cluster, String symbol, EventOutcome.Horizon h, double excess) {
        return EventOutcome.builder()
                .clusterId(cluster).instrumentId(1L).symbol(symbol)
                .horizon(h).excessReturnVsNifty(excess).rawReturn(excess).build();
    }
}
