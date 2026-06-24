package org.amit.finwise.company.model;

import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.service.research.EventStudyService;
import org.amit.finwise.policy.model.PolicyEventCard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Beginner-facing, six-card single-company intelligence profile
 * (Roadmap Phase 1). Almost everything here is assembled by joining data the
 * platform already persists — {@link StockDeepDive} for the heavy analytics plus
 * the {@code marketdata} corporate-action / ownership / deal tables — with a thin
 * relative-strength and event-study layer on top.
 *
 * Every card degrades gracefully: missing inputs leave fields null and are listed
 * in {@link #dataGaps} rather than failing the request.
 */
public record CompanyProfile(

        String symbol,
        LocalDate asOf,
        boolean knownSymbol,
        boolean hasPriceHistory,
        String sector,

        QuoteContext quoteContext,                 // Card 1
        CorporateActionsCard corporateActions,     // Card 2
        OwnershipCard ownership,                    // Card 3
        FundamentalsCard fundamentals,             // Card 4
        RiskFitCard riskFit,                        // Card 5
        NewsPolicyCard newsPolicy,                 // Card 6

        /** Phase 4 — plain-English one-liner per card. Null when narration is off. */
        Map<String, String> beginnerNarrations,

        List<String> dataGaps

) {

    /** Card 1 — live quote, 52-week context and relative strength vs index/sector. */
    public record QuoteContext(
            Double lastClose,
            Double dayChangePercent,
            boolean hitCircuitToday,
            String circuitType,
            Double week52High,
            Double week52Low,
            Double pricePercentileInRange,   // 0..1 — where the last price sits in the 52w band
            Double volumeZScore,             // latest volume vs trailing 20-day mean/stdev
            List<RelativeStrength> relativeStrength
    ) {}

    /** Excess return of the stock over Nifty (and its sector index) for one window. */
    public record RelativeStrength(
            String window,                   // "1M" / "3M" / "1Y"
            Double stockReturnPct,
            Double niftyReturnPct,
            Double vsNiftyPct,               // stock − Nifty
            String sectorIndex,              // resolved sector index ticker, null if none
            Double sectorReturnPct,
            Double vsSectorPct               // stock − sector
    ) {}

    /** Card 2 — past corporate actions, forward calendar and post-results drift. */
    public record CorporateActionsCard(
            List<PastAction> pastActions,
            List<ForwardEvent> forwardEvents,
            EventStudyService.EventStudyResult postResultsDrift   // null when too few events
    ) {}

    public record PastAction(
            String actionType,
            String subject,
            LocalDate exDate,
            LocalDate recordDate,
            BigDecimal dividendAmount,
            Integer ratioNew,
            Integer ratioOld,
            Double dividendYieldPct          // dividend / last price × 100 (current-price yield)
    ) {}

    public record ForwardEvent(
            String eventType,
            LocalDate eventDate,
            String description,
            long daysUntil
    ) {}

    /** Card 3 — shareholding trend, promoter pledge, smart-money deals. */
    public record OwnershipCard(
            List<ShareholdingQuarter> shareholdingTrend,   // newest first, up to 4 quarters
            BigDecimal latestPromoterPledgePct,
            Integer ownershipMomentum,                     // sign(ΔFII + ΔDII): -1 / 0 / +1
            String ownershipMomentumLabel,
            List<RecentDeal> recentDeals
    ) {}

    public record ShareholdingQuarter(
            LocalDate periodEnded,
            BigDecimal promoterPct,
            BigDecimal promoterPledgePct,
            BigDecimal fiiPct,
            BigDecimal diiPct,
            BigDecimal publicPct,
            BigDecimal promoterQoqDelta,
            BigDecimal fiiQoqDelta,
            BigDecimal diiQoqDelta
    ) {}

    public record RecentDeal(
            LocalDate dealDate,
            String dealType,
            String clientName,
            String side,
            Long quantity,
            BigDecimal price
    ) {}

    /** Card 4 — fundamentals (already computed) plus peer-relative verdict lines. */
    public record FundamentalsCard(
            StockFundamentals fundamentals,
            List<String> peerVerdicts
    ) {}

    /** Card 5 — risk metrics and portfolio fit, surfaced from the deep dive. */
    public record RiskFitCard(
            StockDeepDive.PortfolioFit portfolioFit,
            StockDeepDive.RiskMetrics riskMetrics
    ) {}

    /** Card 6 — recent stock news plus the stock's policy exposure (Phase 2.3 bridge). */
    public record NewsPolicyCard(
            List<NewsArticle> news,
            List<PolicyEventCard> policyExposure
    ) {}
}
