package org.amit.finwise.cfo.service.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.service.FinancialSentimentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates Tier 1 + Tier 2 classification per article.
 *
 * Tier 3 (LLM) runs asynchronously after the fetch cycle — this pipeline
 * only does the synchronous, fast (~2ms) work.
 *
 * LLM routing decision is made HERE with explicit criteria so it's
 * easy to audit what goes to Ollama and why.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsClassificationPipeline {

    private final FinancialSentimentService sentimentService;
    private final SymbolExtractorService symbolExtractorService;

    // ── LLM routing thresholds ────────────────────────────────────────────────
    // Tuned for local Ollama (slow) — only send where Tier 1 genuinely needs help.

    /** Articles below this relevance are never sent to LLM — not worth the inference time */
    private static final int LLM_MIN_RELEVANCE = 30;

    /** Confidence below this threshold triggers LLM review (for relevant articles) */
    private static final double LLM_CONFIDENCE_THRESHOLD = 0.55;

    /** Categories that are inherently complex → always send to LLM if relevant */
    private static final List<String> ALWAYS_REVIEW_CATEGORIES = List.of(
            "GEOPOLITICAL", "MACRO_RISK", "MACRO"
    );

    // ══════════════════════════════════════════════════════════════════════════

    public NewsClassification classify(String title, String summary) {

        // Tier 1: market-impact sentiment + category
        FinancialSentimentService.SentimentResult tier1 =
                sentimentService.analyse(title, summary);

        // Tier 2: gazetteer NER → symbols + sectors
        // Pass Tier 1 category so Tier 2 can apply negative constraints
        // (e.g. suppress Pharma sector tag on ENERGY/GEOPOLITICAL articles)
        SymbolExtractorService.ExtractionResult tier2 =
                symbolExtractorService.extract(title, summary, tier1.category());

        // Merge sector data: tier1 has domain-rule sectors, tier2 has gazetteer sectors
        // Keep both; LLM can disambiguate if needed
        String mergedSectorImpact = tier1.sectorImpact();

        // Relevance score
        int relevanceScore = computeRelevanceScore(tier1, tier2);
        NewsArticle.RelevanceLevel relevanceLevel = scoreToLevel(relevanceScore);

        // Map enums
        NewsArticle.Sentiment sentiment = switch (tier1.sentiment()) {
            case "POSITIVE" -> NewsArticle.Sentiment.POSITIVE;
            case "NEGATIVE" -> NewsArticle.Sentiment.NEGATIVE;
            default         -> NewsArticle.Sentiment.NEUTRAL;
        };

        NewsArticle.Category category;
        try {
            category = NewsArticle.Category.valueOf(tier1.category());
        } catch (IllegalArgumentException _) {
            category = NewsArticle.Category.MARKET;
        }

        NewsArticle.ImpactType impactType;
        try {
            impactType = NewsArticle.ImpactType.valueOf(tier1.impactType());
        } catch (IllegalArgumentException _) {
            impactType = NewsArticle.ImpactType.MACRO;
        }

        NewsArticle.ImpactHorizon impactHorizon;
        try {
            impactHorizon = NewsArticle.ImpactHorizon.valueOf(tier1.impactHorizon());
        } catch (IllegalArgumentException _) {
            impactHorizon = NewsArticle.ImpactHorizon.MEDIUM_TERM;
        }

        // Actionability score (computed deterministically from signals)
        int actionability = computeActionabilityScore(
                relevanceScore, impactHorizon, tier2.symbols(), tier1.rawScore(), category);

        // LLM routing decision
        boolean needsLlmReview = shouldSendToLlm(
                relevanceScore, tier1.confidence(), tier1.category(), tier2.symbols());

        return new NewsClassification(
                sentiment,
                tier1.rawScore(),
                category,
                impactType,
                impactHorizon,
                mergedSectorImpact,
                tier2.symbols(),
                tier2.isins(),
                tier2.sectors(),
                relevanceScore,
                relevanceLevel,
                actionability,
                tier1.confidence(),
                needsLlmReview
        );
    }

    // ── LLM Routing ───────────────────────────────────────────────────────────

    /**
     * Decides whether an article should be sent to the local Ollama LLM.
     *
     * Rules (checked in order):
     *  1. Never send LOW-relevance articles (score < 30) — not worth inference time
     *  2. Always send GEOPOLITICAL / MACRO_RISK / MACRO if relevant — inherently complex
     *  3. Send if has portfolio symbols AND confidence is uncertain
     *  4. Send if HIGH relevance AND confidence is uncertain
     *  5. Otherwise skip — Tier 1 is good enough
     */
    private boolean shouldSendToLlm(int relevanceScore, double confidence,
                                     String category, List<String> symbols) {
        // Rule 1: not relevant enough to spend LLM time
        if (relevanceScore < LLM_MIN_RELEVANCE) return false;

        // Rule 2: complex categories always need nuanced analysis
        if (ALWAYS_REVIEW_CATEGORIES.contains(category) && relevanceScore >= 50) return true;

        // Rule 3: affects specific holdings → get the analysis right
        if (!symbols.isEmpty() && confidence < LLM_CONFIDENCE_THRESHOLD) return true;

        // Rule 4: high-relevance but uncertain
        if (relevanceScore >= 70 && confidence < LLM_CONFIDENCE_THRESHOLD) return true;

        return false;
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private int computeRelevanceScore(FinancialSentimentService.SentimentResult tier1,
                                       SymbolExtractorService.ExtractionResult tier2) {
        int score = 10;  // baseline

        // Direct symbol matches → high relevance
        if (!tier2.symbols().isEmpty()) {
            score = 75 + tier2.symbols().size() * 5;
        } else if (!tier2.sectors().isEmpty()) {
            score = 45 + tier2.sectors().size() * 5;
        }

        // Strong market signal → bump
        int absSentiment = Math.abs(tier1.rawScore());
        if      (absSentiment >= 5) score += 10;
        else if (absSentiment >= 3) score += 5;

        // High-priority categories get a bump
        score += switch (tier1.category()) {
            case "EARNINGS", "CORPORATE_ACTION", "IPO", "ANALYST_RECOMMENDATION" -> 10;
            case "MACRO_RISK", "GEOPOLITICAL"                                      -> 8;
            case "POLICY"                                                          -> 5;
            default -> 0;
        };

        return Math.min(100, score);
    }

    /**
     * Actionability: how likely is this news to require the investor to act?
     * This is a portfolio-decision signal, not just relevance.
     */
    private int computeActionabilityScore(int relevanceScore, NewsArticle.ImpactHorizon horizon,
                                           List<String> symbols, int sentimentScore,
                                           NewsArticle.Category category) {
        int score = relevanceScore / 2;  // start from half the relevance

        // Short-term impact means act now
        score += switch (horizon) {
            case SHORT_TERM  -> 20;
            case MEDIUM_TERM -> 10;
            case LONG_TERM   -> 0;
        };

        // Stock-specific news is more actionable than macro
        if (!symbols.isEmpty()) score += 15;

        // Strong directional signal
        int absScore = Math.abs(sentimentScore);
        if      (absScore >= 5) score += 15;
        else if (absScore >= 3) score += 10;

        // Regulatory/SEBI orders are important to know but usually not immediately actionable
        if (category == NewsArticle.Category.REGULATORY) score -= 15;

        return Math.clamp(score, 0, 100);
    }

    private NewsArticle.RelevanceLevel scoreToLevel(int score) {
        if (score >= 70) return NewsArticle.RelevanceLevel.HIGH;
        if (score >= 40) return NewsArticle.RelevanceLevel.MEDIUM;
        return NewsArticle.RelevanceLevel.LOW;
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record NewsClassification(
            NewsArticle.Sentiment      sentiment,
            int                        sentimentScore,
            NewsArticle.Category       category,
            NewsArticle.ImpactType     impactType,
            NewsArticle.ImpactHorizon  impactHorizon,
            String                     sectorImpact,       // "Banking:NEGATIVE,IT:POSITIVE"
            java.util.List<String>     symbols,
            java.util.List<String>     isins,
            java.util.List<String>     sectors,
            int                        relevanceScore,
            NewsArticle.RelevanceLevel relevanceLevel,
            int                        actionabilityScore,
            double                     confidence,
            boolean                    needsLlmReview
    ) {}
}
