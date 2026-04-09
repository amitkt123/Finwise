package org.amit.expensetracker.cfo.model;

import java.util.List;

/**
 * Full response from GET /api/cfo/news/personalized.
 *
 * Includes the ranked news items plus lightweight summaries of the
 * behavioral profile and market context used to compute the scores —
 * useful for UI display ("Based on your behavior: Long-term Holder")
 * and for debugging the scoring.
 */
public record PersonalizedNewsResponse(

        List<PersonalizedNewsItem> news,

        /** Total articles found before applying the limit */
        int totalCount,

        /** Lightweight behavioral profile summary (null if no profile yet) */
        BehaviorSummary behaviorProfile,

        /** Lightweight market context summary */
        MarketSummary marketContext

) {

    public record BehaviorSummary(
            String tradingStyle,
            double patienceIndex,
            double winRate,
            String derivedRiskAppetite,
            /** True if derived risk appetite differs significantly from stated */
            boolean divergesFromStated
    ) {}

    public record MarketSummary(
            String marketMood,
            boolean isVolatile,
            double sevenDaySentimentScore,
            double portfolioTrend
    ) {}
}
