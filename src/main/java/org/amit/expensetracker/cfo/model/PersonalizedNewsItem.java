package org.amit.expensetracker.cfo.model;

import java.util.List;

/**
 * A news article enriched with investor-specific scoring breakdown.
 * Produced by PersonalizedRelevanceScorer for each article.
 *
 * All boost/modifier fields are included so the UI and LLM can understand
 * *why* an article scored the way it did.
 */
public record PersonalizedNewsItem(

        NewsArticle article,

        /** Final investor-specific score [0-100], after all pillars applied */
        int personalizedScore,

        /** Original generic score from NewsClassificationPipeline */
        int baseRelevanceScore,

        /** Pillar 1a: how much of your portfolio is in the mentioned stocks (+0 to +50) */
        double portfolioWeightBoost,

        /** Pillar 1b: indirect sector impact via SectorCorrelationMap (+0 to +20) */
        double correlationBoost,

        /** Pillar 2: alignment with active financial goals (+0 to +20) */
        int goalAlignmentBoost,

        /** Pillar 3: behavior alignment multiplier (0.5–1.5) */
        double behaviorAlignmentFactor,

        /** Pillar 4: market condition modifier (-10 to +10) */
        int marketConditionModifier,

        /** Indian market: tax-harvest boost in March for losing positions (+0 or +15) */
        int taxHarvestBoost,

        /** The held symbol with the largest portfolio weight that matched this article */
        String primaryMatchSymbol,

        /**
         * Human-readable reason for the correlation boost.
         * e.g. "Crude Oil rise → negative for Paint"
         */
        String primaryCorrelationReason,

        /** Names of active financial goals this article is relevant to */
        List<String> alignedGoalNames,

        /** What action this article implies for the investor */
        ActionType actionType,

        /**
         * True when the investor has high behavioral divergence (hypocrisyScore > 60)
         * and this is a NEGATIVE HIGH-relevance article.
         * Triggers "Voice of Reason" tone in LLM advice.
         */
        boolean psychologicalGuardrail,

        /**
         * True when a held stock has hit circuit limits on 2+ consecutive days.
         * Signals anomalous price action regardless of article sentiment.
         */
        boolean anomalyAlert
) {}
