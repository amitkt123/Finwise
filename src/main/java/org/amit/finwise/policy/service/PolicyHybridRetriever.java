package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hybrid policy-chunk retrieval (Roadmap Phase 2.2).
 *
 * Policy text is reference-heavy, so neither retrieval mode alone is enough:
 * lexical FTS preserves exact-match precision (e.g. {@code RBI/2024-25/123}), while
 * vector search adds semantic recall ("what affects my IT stocks"). The two ranked
 * lists are fused with Reciprocal Rank Fusion:
 *   score(d) = Σ_i 1 / (k + rank_i(d))
 * which needs no score normalisation across the two very different scales.
 *
 * When embeddings are unavailable the retriever transparently falls back to the
 * existing pure-lexical path, so behaviour is never worse than before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyHybridRetriever {

    private final PolicyChunkRepository chunkRepository;
    private final PolicyEmbeddingService policyEmbeddingService;
    private final EmbeddingService embeddingService;

    /** RRF damping constant; 60 is the value from the original Cormack et al. paper. */
    private static final int RRF_K = 60;

    public List<PolicyChunk> retrieve(String query, int limit) {
        List<PolicyChunk> lexical = chunkRepository.searchCurrentChunks(query);

        if (!policyEmbeddingService.isAvailable()) {
            return lexical.stream().limit(limit).toList();
        }
        float[] queryVector = embeddingService.embedQuery(query);
        if (queryVector == null) {
            return lexical.stream().limit(limit).toList();
        }

        int candidatePool = Math.max(limit * 3, 30);
        List<PolicyEmbeddingService.ScoredChunk> vector =
                policyEmbeddingService.searchSimilar(queryVector, candidatePool);
        if (vector.isEmpty()) {
            return lexical.stream().limit(limit).toList();
        }

        Map<Long, Double> fused = new HashMap<>();
        Map<Long, PolicyChunk> chunkById = new LinkedHashMap<>();

        int rank = 0;
        for (PolicyChunk chunk : lexical) {
            if (rank >= candidatePool) break;
            if (chunk.getId() == null) continue;
            fused.merge(chunk.getId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            chunkById.putIfAbsent(chunk.getId(), chunk);
            rank++;
        }

        rank = 0;
        for (PolicyEmbeddingService.ScoredChunk sc : vector) {
            if (sc.chunkId() == null) continue;
            fused.merge(sc.chunkId(), 1.0 / (RRF_K + rank + 1), Double::sum);
            rank++;
        }

        // Load chunks that surfaced only via the vector path.
        List<Long> missing = fused.keySet().stream()
                .filter(id -> !chunkById.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            for (PolicyChunk chunk : chunkRepository.findAllById(missing)) {
                chunkById.put(chunk.getId(), chunk);
            }
        }

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(e -> chunkById.get(e.getKey()))
                .filter(Objects::nonNull)
                .limit(limit)
                .toList();
    }
}
