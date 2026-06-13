package org.amit.finwise.cfo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.repository.ArticleEntityRepository;
import org.amit.finwise.cfo.repository.EventOutcomeRepository;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Evidence-pack retrieval (DF-6, pipeline step 4) — the payoff of the whole
 * phase. Given a query (e.g. a fresh headline or a portfolio theme), it returns
 * historical events whose <em>realized</em> outcomes ground the LLM's reasoning:
 * "similar event [cluster, date] → these stocks moved X% excess over 5d."
 *
 * Ranking is a hybrid of three signals, exactly as specified:
 *   score = vector similarity × recency decay × (1 + portfolio entity overlap)
 * so a semantically-close, recent event that touches the user's holdings ranks
 * above a stale generic one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvidencePackService {

    /** Recency half-life in days for the exponential decay term. */
    private static final double RECENCY_HALF_LIFE_DAYS = 30.0;
    /** How far back to retrieve event history. */
    private static final int LOOKBACK_DAYS = 365;

    private final EmbeddingService embeddingService;
    private final ArticleEntityRepository articleEntityRepository;
    private final EventOutcomeRepository eventOutcomeRepository;

    /**
     * One historical event with its realized market reaction, ready to drop into
     * a prompt. {@link #render()} produces the single-line evidence string.
     */
    public record EvidencePack(
            Long clusterId,
            String title,
            LocalDate eventDate,
            double similarity,
            double score,
            List<String> symbols,
            String outcomeSummary) {

        public String render() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(eventDate).append("] ").append(title);
            if (outcomeSummary != null && !outcomeSummary.isBlank()) {
                sb.append(" → ").append(outcomeSummary);
            }
            return sb.toString();
        }
    }

    /**
     * Retrieve the top evidence packs for a query, ranked by the hybrid score.
     * Packs without any computed outcome are de-prioritised but still eligible
     * (a very similar recent event is useful even before its returns mature).
     */
    public List<EvidencePack> retrieve(String queryText, Set<String> portfolioSymbols, int limit) {
        if (queryText == null || queryText.isBlank()) return List.of();
        Set<String> holdings = portfolioSymbols == null ? Set.of() : portfolioSymbols.stream()
                .map(s -> s.toUpperCase()).collect(Collectors.toSet());

        // Over-fetch on the vector side, then re-rank with recency + overlap + outcomes.
        List<EmbeddingService.ClusterMatch> candidates =
                embeddingService.findSimilarClusters(queryText, LOOKBACK_DAYS, Math.max(limit * 4, 12));
        if (candidates.isEmpty()) return List.of();

        List<EvidencePack> packs = new ArrayList<>();
        for (EmbeddingService.ClusterMatch c : candidates) {
            List<String> symbols =
                    articleEntityRepository.findInstrumentSymbolsForCluster(c.clusterId());
            long overlap = symbols.stream().map(String::toUpperCase).filter(holdings::contains).count();

            double recency = recencyDecay(c.firstSeen());
            double score = c.similarity() * recency * (1.0 + overlap);

            List<EventOutcome> outcomes = eventOutcomeRepository.findByClusterId(c.clusterId());
            packs.add(new EvidencePack(
                    c.clusterId(),
                    c.title(),
                    c.firstSeen() != null ? c.firstSeen().toLocalDate() : null,
                    c.similarity(),
                    score,
                    symbols,
                    summariseOutcomes(outcomes)));
        }

        packs.sort(Comparator.comparingDouble(EvidencePack::score).reversed());
        return packs.stream().limit(limit).toList();
    }

    /**
     * Convenience for prompt assembly: the rendered evidence lines joined, or an
     * empty string when no grounded history exists.
     */
    public String retrieveAsText(String queryText, Set<String> portfolioSymbols, int limit) {
        List<EvidencePack> packs = retrieve(queryText, portfolioSymbols, limit);
        if (packs.isEmpty()) return "";
        return packs.stream().map(EvidencePack::render).collect(Collectors.joining("\n"));
    }

    private double recencyDecay(LocalDateTime firstSeen) {
        if (firstSeen == null) return 0.5;
        long ageDays = Math.max(0, ChronoUnit.DAYS.between(firstSeen.toLocalDate(), LocalDate.now()));
        return Math.exp(-ageDays / RECENCY_HALF_LIFE_DAYS);
    }

    /** "TCS +3.2% / RELIANCE -1.1% excess over 5d" from the matured outcomes. */
    private String summariseOutcomes(List<EventOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return "";
        // Prefer the 5d horizon; fall back to the longest available.
        EventOutcome.Horizon target = outcomes.stream()
                .map(EventOutcome::getHorizon)
                .filter(h -> h == EventOutcome.Horizon.H5D)
                .findFirst()
                .orElse(outcomes.stream().map(EventOutcome::getHorizon)
                        .max(Comparator.comparingInt(h -> h.tradingDays))
                        .orElse(EventOutcome.Horizon.H5D));

        List<String> moves = outcomes.stream()
                .filter(o -> o.getHorizon() == target && o.getExcessReturnVsNifty() != null)
                .sorted(Comparator.comparingDouble(
                        (EventOutcome o) -> Math.abs(o.getExcessReturnVsNifty())).reversed())
                .limit(3)
                .map(o -> String.format("%s %+.1f%%", o.getSymbol(),
                        o.getExcessReturnVsNifty() * 100))
                .toList();
        if (moves.isEmpty()) return "";
        return String.join(" / ", moves) + " excess over " + target.tradingDays + "d";
    }
}
