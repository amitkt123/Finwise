package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Derives current market conditions from stored data:
 *   - News sentiment (last 7 days)
 *   - Per-sector mood from sectorImpact fields
 *   - Volatility detection (HIGH NEGATIVE count in 3 days)
 *   - Portfolio trend (latest snapshot vs 7-days-ago)
 *   - Price-based volatility from StockPriceHistory
 *
 * In-memory cached with a 6-hour TTL. Invalidated by CFOScheduler every 6 hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketContextService {

    private final NewsArticleRepository newsRepo;
    private final PortfolioSnapshotRepository snapshotRepo;
    private final StockPriceHistoryRepository priceRepo;
    private final InvestmentRepository investmentRepo;

    private static final int CACHE_HOURS = 6;
    private static final int VOLATILE_THRESHOLD = 5;   // HIGH NEGATIVE articles in 3 days

    // ── In-memory cache ───────────────────────────────────────────────────────

    private volatile MarketContextSnapshot cachedSnapshot = null;
    private volatile LocalDateTime cacheExpiry = LocalDateTime.MIN;

    // ── Public API ────────────────────────────────────────────────────────────

    public MarketContextSnapshot getMarketContext(String userId) {
        if (cachedSnapshot == null || LocalDateTime.now().isAfter(cacheExpiry)) {
            cachedSnapshot = compute(userId);
            cacheExpiry = LocalDateTime.now().plusHours(CACHE_HOURS);
            log.debug("[MarketContext] Refreshed: mood={}, volatile={}",
                    cachedSnapshot.overallMarketMood(), cachedSnapshot.isVolatileMarket());
        }
        return cachedSnapshot;
    }

    public void invalidateCache() {
        cacheExpiry = LocalDateTime.MIN;
        log.debug("[MarketContext] Cache invalidated");
    }

    // ── Snapshot record ───────────────────────────────────────────────────────

    public record MarketContextSnapshot(
            double sevenDaySentimentScore,           // -1.0 (very bearish) to +1.0 (very bullish)
            MarketMood overallMarketMood,
            Map<String, Double> sectorSentimentScores, // sector → sentiment score (-1 to +1)
            Map<String, String> sectorMoods,            // sector → "BULLISH" | "BEARISH" | "NEUTRAL"
            int highRelevanceNegativeCount3Days,
            boolean isVolatileMarket,
            double portfolioTrend,            // % change over last 7 days (may be 0 if no data)
            double priceBasedVolatility,      // avg |priceChangePercent| across portfolio stocks
            LocalDateTime computedAt
    ) {
        public enum MarketMood { BULLISH, BEARISH, NEUTRAL, VOLATILE }
    }

    // ── Private computation ───────────────────────────────────────────────────

    private MarketContextSnapshot compute(String userId) {
        LocalDate sevenDaysAgo = LocalDate.now().minusDays(7);
        LocalDate threeDaysAgo = LocalDate.now().minusDays(3);

        // ── 1. Fetch recent articles ─────────────────────────────────────────
        List<NewsArticle> recentArticles = newsRepo.findRecentForSentimentAggregation(sevenDaysAgo);

        // ── 2. sevenDaySentimentScore (weighted by relevanceScore) ───────────
        double weightedSentimentSum = 0;
        double totalWeight = 0;
        for (NewsArticle a : recentArticles) {
            double weight = a.getRelevanceScore() != null ? a.getRelevanceScore() : 10;
            double sentiment = sentimentValue(a.getSentiment());
            weightedSentimentSum += sentiment * weight;
            totalWeight += weight;
        }
        double sevenDaySentiment = totalWeight > 0 ? weightedSentimentSum / totalWeight : 0.0;

        // ── 3. Sector sentiment scores ────────────────────────────────────────
        // sectorImpact format: "Banking:NEGATIVE,Aviation:POSITIVE,FMCG:NEGATIVE"
        Map<String, List<Double>> sectorScoreAccum = new HashMap<>();
        for (NewsArticle a : recentArticles) {
            if (a.getSectorImpact() == null || a.getSectorImpact().isBlank()) continue;
            double weight = a.getRelevanceScore() != null ? a.getRelevanceScore() / 100.0 : 0.1;
            for (String entry : a.getSectorImpact().split(",")) {
                String[] parts = entry.trim().split(":");
                if (parts.length != 2) continue;
                String sector = parts[0].trim();
                String sentiment = parts[1].trim();
                double val = switch (sentiment.toUpperCase()) {
                    case "POSITIVE" ->  weight;
                    case "NEGATIVE" -> -weight;
                    default         ->  0.0;
                };
                sectorScoreAccum.computeIfAbsent(sector, _ -> new ArrayList<>()).add(val);
            }
        }

        Map<String, Double> sectorSentimentScores = new HashMap<>();
        Map<String, String> sectorMoods = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : sectorScoreAccum.entrySet()) {
            double avg = e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            sectorSentimentScores.put(e.getKey(), avg);
            sectorMoods.put(e.getKey(),
                    avg > 0.2 ? "BULLISH" : avg < -0.2 ? "BEARISH" : "NEUTRAL");
        }

        // ── 4. isVolatileMarket ───────────────────────────────────────────────
        List<NewsArticle> highNegRecent = newsRepo.findHighRelevanceNegativeSince(threeDaysAgo);
        int highNegCount = highNegRecent.size();
        boolean isVolatile = highNegCount >= VOLATILE_THRESHOLD;

        // ── 5. Portfolio trend ────────────────────────────────────────────────
        double portfolioTrend = 0.0;
        Optional<PortfolioSnapshot> latestOpt = snapshotRepo
                .findTopByUserIdOrderBySnapshotTimeDesc(userId);
        Optional<PortfolioSnapshot> weekAgoOpt = snapshotRepo
                .findTopByUserIdAndSnapshotTimeBeforeOrderBySnapshotTimeDesc(
                        userId, LocalDateTime.now().minusDays(7));

        if (latestOpt.isPresent() && weekAgoOpt.isPresent()) {
            BigDecimal latest = latestOpt.get().getCurrentValue();
            BigDecimal past   = weekAgoOpt.get().getCurrentValue();
            if (latest != null && past != null && past.compareTo(BigDecimal.ZERO) > 0) {
                portfolioTrend = (latest.doubleValue() - past.doubleValue())
                        / past.doubleValue() * 100.0;
            }
        }

        // ── 6. Price-based volatility ─────────────────────────────────────────
        List<Investment> activeInvestments = investmentRepo.findActiveInvestments(userId);
        List<String> heldSymbols = activeInvestments.stream()
                .filter(i -> i.getSymbol() != null)
                .map(i -> i.getSymbol().toUpperCase())
                .distinct()
                .toList();

        double priceVolatility = 0.0;
        if (!heldSymbols.isEmpty()) {
            List<StockPriceHistory> recentPrices = priceRepo.findRecentBySymbols(
                    heldSymbols, LocalDate.now().minusDays(7));
            OptionalDouble avgVolatility = recentPrices.stream()
                    .filter(p -> p.getPriceChangePercent() != null)
                    .mapToDouble(p -> Math.abs(p.getPriceChangePercent()))
                    .average();
            priceVolatility = avgVolatility.orElse(0.0);
        }

        // ── 7. Overall market mood ────────────────────────────────────────────
        MarketContextSnapshot.MarketMood mood;
        if (isVolatile)                        mood = MarketContextSnapshot.MarketMood.VOLATILE;
        else if (sevenDaySentiment < -0.25)    mood = MarketContextSnapshot.MarketMood.BEARISH;
        else if (sevenDaySentiment > 0.25)     mood = MarketContextSnapshot.MarketMood.BULLISH;
        else                                   mood = MarketContextSnapshot.MarketMood.NEUTRAL;

        return new MarketContextSnapshot(
                sevenDaySentiment,
                mood,
                Collections.unmodifiableMap(sectorSentimentScores),
                Collections.unmodifiableMap(sectorMoods),
                highNegCount,
                isVolatile,
                portfolioTrend,
                priceVolatility,
                LocalDateTime.now()
        );
    }

    private double sentimentValue(NewsArticle.Sentiment s) {
        if (s == null) return 0.0;
        return switch (s) {
            case POSITIVE -> 1.0;
            case NEGATIVE -> -1.0;
            case NEUTRAL  ->  0.0;
        };
    }
}
