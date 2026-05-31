package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Classifies user chat messages into one of three intent buckets.
 *
 * RESEARCH      — user is asking about a specific named stock (buy/sell/analyse).
 *                 Triggers the StockIntelligenceService evidence-pack path.
 * PLANNING      — user is asking about goals, savings targets, asset allocation.
 *                 No symbol routing; CFO uses the goals-first context layout.
 * PORTFOLIO_REVIEW — default; user wants a portfolio or market overview.
 *
 * Classification uses a two-step heuristic before falling back to PORTFOLIO_REVIEW:
 *   1. Symbol presence (via SymbolExtractorService.extract)
 *   2. Research verb co-occurrence with that symbol
 *
 * This is intentionally a fast, low-latency path — no LLM call.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentClassifier {

    private final SymbolExtractorService symbolExtractorService;

    // ── Verb lists ────────────────────────────────────────────────────────────

    private static final Set<String> RESEARCH_VERBS = Set.of(
            "buy", "sell", "invest", "should i", "worth buying", "worth investing",
            "good buy", "analysis", "analyse", "analyze", "research",
            "recommendation", "target price", "entry", "exit", "hold", "avoid",
            "bullish", "bearish", "outlook", "view on", "thoughts on",
            "is it a buy", "is it good", "kya lagta", "lena chahiye",
            "overvalued", "undervalued", "cheap", "expensive",
            "pe ratio", "roe", "fundamentals", "technicals", "chart"
    );

    private static final Set<String> PLANNING_KEYWORDS = Set.of(
            "goal", "goals", "retirement", "savings", "sip", "monthly",
            "emergency fund", "corpus", "target", "horizon", "long term",
            "portfolio allocation", "rebalance", "asset allocation",
            "financial plan", "wealth creation"
    );

    // ─────────────────────────────────────────────────────────────────────────

    public enum Intent { RESEARCH, PLANNING, PORTFOLIO_REVIEW }

    /**
     * Classified intent with the extracted symbol (null for non-RESEARCH intents).
     */
    public record ClassifiedIntent(Intent type, String symbol, double confidence) {}

    /**
     * Classify the user message.
     *
     * @param message raw user chat message
     * @return ClassifiedIntent with type and optional NSE symbol
     */
    public ClassifiedIntent classify(String message) {
        if (message == null || message.isBlank()) {
            return new ClassifiedIntent(Intent.PORTFOLIO_REVIEW, null, 1.0);
        }

        String lower = message.toLowerCase();

        // ── Planning check (no symbol needed) ────────────────────────────────
        for (String kw : PLANNING_KEYWORDS) {
            if (lower.contains(kw)) {
                log.debug("[IntentClassifier] PLANNING — keyword '{}'", kw);
                return new ClassifiedIntent(Intent.PLANNING, null, 0.8);
            }
        }

        // ── Research check: symbol + verb ─────────────────────────────────────
        SymbolExtractorService.ExtractionResult extraction =
                symbolExtractorService.extract(message, "");

        List<String> symbols = extraction.symbols();
        if (!symbols.isEmpty()) {
            boolean hasResearchVerb = RESEARCH_VERBS.stream().anyMatch(lower::contains);
            // Even without a verb, a direct ticker mention in a short message is likely research
            boolean shortMessage = message.trim().split("\\s+").length <= 6;

            if (hasResearchVerb || shortMessage) {
                String symbol = symbols.getFirst();
                double conf = hasResearchVerb ? 0.9 : 0.7;
                log.debug("[IntentClassifier] RESEARCH — symbol={}, hasVerb={}", symbol, hasResearchVerb);
                return new ClassifiedIntent(Intent.RESEARCH, symbol, conf);
            }
        }

        log.debug("[IntentClassifier] PORTFOLIO_REVIEW (default)");
        return new ClassifiedIntent(Intent.PORTFOLIO_REVIEW, null, 1.0);
    }
}
