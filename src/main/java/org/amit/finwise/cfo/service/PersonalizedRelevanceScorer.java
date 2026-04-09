package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.*;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes investor-specific relevance scores for news articles.
 *
 * Applies four pillars on top of the generic baseRelevanceScore:
 *   Pillar 1: Portfolio weight + sector correlation
 *   Pillar 2: Goal alignment
 *   Pillar 3: Investor behavior alignment  (→ Phase 4 for full implementation)
 *   Pillar 4: Market condition modifier    (→ Phase 7 for full implementation)
 *
 * Plus Indian-market triggers: tax harvesting, corporate action multiplier,
 * circuit breaker anomaly, and cash position awareness.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalizedRelevanceScorer {

    private final NewsArticleRepository newsRepo;
    private final InvestmentRepository investmentRepo;
    private final FinancialGoalRepository goalRepo;
    private final InvestorBehaviorService behaviorService;
    private final MarketContextService marketContextService;
    private final StockPriceHistoryRepository priceRepo;

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Scores recent articles with investor-specific personalization.
     *
     * @param userId   the investor
     * @param daysBack how many days back to fetch articles
     * @param limit    maximum number of articles to return (after sorting)
     * @return full personalized response with scoring breakdown
     */
    public PersonalizedNewsResponse score(String userId, int daysBack, int limit) {
        LocalDate since = LocalDate.now().minusDays(daysBack);
        List<NewsArticle> articles = newsRepo.findRecentByRelevance(since);

        if (articles.isEmpty()) {
            return new PersonalizedNewsResponse(List.of(), 0, null, null);
        }

        // Load investor context (single DB fetch each — fast)
        List<Investment> activeInvestments = investmentRepo.findActiveInvestments(userId);
        BigDecimal totalCost = investmentRepo.totalInvestmentCost(userId);
        List<FinancialGoal> activeGoals = goalRepo.findActiveGoals(userId);
        Optional<InvestorBehaviorProfile> behavior = behaviorService.getProfile(userId);
        MarketContextService.MarketContextSnapshot marketContext =
                marketContextService.getMarketContext(userId);

        // Build symbol→weight and sector→weight maps
        Map<String, Double> symbolWeights = buildSymbolWeights(activeInvestments, totalCost);
        Map<String, Double> sectorWeights = buildSectorWeights(activeInvestments, totalCost);
        Set<String> heldSymbols = symbolWeights.keySet();

        // Score all articles
        List<PersonalizedNewsItem> scored = articles.stream()
                .map(article -> scoreArticle(
                        article, symbolWeights, sectorWeights, heldSymbols,
                        activeInvestments, activeGoals, behavior.orElse(null),
                        marketContext))
                .sorted(Comparator.comparingInt(PersonalizedNewsItem::personalizedScore).reversed())
                .limit(limit)
                .toList();

        // Apply volatility normalizer if market is volatile
        List<PersonalizedNewsItem> normalized = marketContext.isVolatileMarket()
                ? applyVolatilityNormalizer(scored)
                : scored;

        // Build summary objects for the response
        PersonalizedNewsResponse.BehaviorSummary behaviorSummary =
                behavior.map(b -> new PersonalizedNewsResponse.BehaviorSummary(
                        b.getTradingStyle() != null ? b.getTradingStyle().name() : "UNKNOWN",
                        b.getPatienceIndex() != null ? b.getPatienceIndex() : 0.0,
                        b.getWinRate() != null ? b.getWinRate() : 0.0,
                        b.getDerivedRiskAppetite() != null ? b.getDerivedRiskAppetite().name() : "UNKNOWN",
                        b.getHypocrisyScore() != null && b.getHypocrisyScore() > 30
                )).orElse(null);

        PersonalizedNewsResponse.MarketSummary marketSummary = new PersonalizedNewsResponse.MarketSummary(
                marketContext.overallMarketMood().name(),
                marketContext.isVolatileMarket(),
                marketContext.sevenDaySentimentScore(),
                marketContext.portfolioTrend()
        );

        return new PersonalizedNewsResponse(normalized, articles.size(), behaviorSummary, marketSummary);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Per-article scoring
    // ══════════════════════════════════════════════════════════════════════════

    private PersonalizedNewsItem scoreArticle(
            NewsArticle article,
            Map<String, Double> symbolWeights,
            Map<String, Double> sectorWeights,
            Set<String> heldSymbols,
            List<Investment> activeInvestments,
            List<FinancialGoal> goals,
            InvestorBehaviorProfile behavior,
            MarketContextService.MarketContextSnapshot marketContext) {

        int base = article.getRelevanceScore() != null ? article.getRelevanceScore() : 0;

        // ── Pillar 1a: Portfolio weight boost ─────────────────────────────────
        List<String> articleSymbols = parseCSV(article.getRelatedSymbols());
        List<String> articleSectors = parseCSV(article.getRelatedSectors());

        double portfolioWeightBoost = 0;
        String primaryMatchSymbol = null;
        String primaryMatchSector = null;

        for (String sym : articleSymbols) {
            Double weight = symbolWeights.get(sym.toUpperCase());
            if (weight != null) {
                double boost = weight * 50;
                if (boost > portfolioWeightBoost) {
                    portfolioWeightBoost = boost;
                    primaryMatchSymbol = sym;
                }
            }
        }

        for (String sector : articleSectors) {
            Double weight = sectorWeights.get(sector);
            if (weight != null) {
                double boost = weight * 25;
                if (boost > portfolioWeightBoost) {
                    portfolioWeightBoost = boost;
                    primaryMatchSector = sector;
                }
            }
        }

        // ── Pillar 1b: Sector correlation boost ───────────────────────────────
        double correlationBoost = 0;
        String primaryCorrelationReason = null;

        String categoryStr = article.getCategory() != null ? article.getCategory().name() : "";
        Map<String, Double> correlatedImpacts = SectorCorrelationMap.evaluate(
                article.getTitle(), categoryStr, article.getSentiment());

        for (Map.Entry<String, Double> entry : correlatedImpacts.entrySet()) {
            String sector = entry.getKey();
            double strength = Math.abs(entry.getValue());
            Double weight = sectorWeights.get(sector);
            if (weight != null && weight > 0) {
                double boost = weight * strength * 20;
                if (boost > correlationBoost) {
                    correlationBoost = boost;
                    primaryCorrelationReason = buildCorrelationReason(
                            article.getTitle(), sector, entry.getValue());
                }
            }
        }

        // ── Pillar 2: Goal alignment ──────────────────────────────────────────
        int goalAlignmentBoost = 0;
        List<String> alignedGoalNames = new ArrayList<>();

        for (FinancialGoal goal : goals) {
            int goalBoost = computeGoalAlignment(article, goal);
            if (goalBoost > 0) {
                alignedGoalNames.add(goal.getName());
            }
            if (goalBoost > goalAlignmentBoost) {
                goalAlignmentBoost = goalBoost;
            }
        }
        goalAlignmentBoost = Math.min(goalAlignmentBoost, 20);

        // ── Pillar 3: Behavior alignment factor ───────────────────────────────
        // Full implementation in Phase 4; returns 1.0 (neutral) until then
        double behaviorFactor = computeBehaviorFactor(article, behavior);

        // ── Pillar 4: Market condition modifier ───────────────────────────────
        // Full implementation in Phase 7; returns 0 until then
        int marketModifier = computeMarketModifier(article, marketContext);

        // ── Indian-market: Tax harvest boost (March) ──────────────────────────
        int taxBoost = 0;
        if (LocalDate.now().getMonth() == Month.MARCH) {
            String titleLower = article.getTitle().toLowerCase();
            if (titleLower.contains("ltcg") || titleLower.contains("stcg")
                    || titleLower.contains("tax") || titleLower.contains("capital gain")) {
                boolean hasLosingPosition = activeInvestments.stream()
                        .filter(inv -> articleSymbols.contains(inv.getSymbol())
                                || articleSectors.contains(inv.getSector()))
                        .anyMatch(inv -> inv.getUnrealizedGainLoss() != null
                                && inv.getUnrealizedGainLoss().compareTo(BigDecimal.ZERO) < 0);
                if (hasLosingPosition) taxBoost = 15;
            }
        }

        // ── ActionType assignment ─────────────────────────────────────────────
        ActionType actionType = assignActionType(
                article, articleSymbols, heldSymbols, activeInvestments, sectorWeights);

        // ── Corporate action multiplier ───────────────────────────────────────
        if (actionType == ActionType.CORPORATE_ACTION_REQUIRED) {
            // Override: corporate actions for held stocks always score HIGH
            base = Math.max(base, 85);
        }

        // ── Anomaly alert (circuit breaker) ───────────────────────────────────
        boolean anomalyAlert = articleSymbols.stream()
                .filter(heldSymbols::contains)
                .anyMatch(sym -> priceRepo.findTopBySymbolOrderByPriceDateDesc(sym)
                        .map(h -> h.getConsecutiveCircuitCount() != null
                                && h.getConsecutiveCircuitCount() >= 2)
                        .orElse(false));
        if (anomalyAlert) base = Math.max(base, 70); // ensure anomalies are visible

        // ── Psychological guardrail ───────────────────────────────────────────
        // Phase 5: enabled when hypocrisyScore > 60 + NEGATIVE market article
        boolean psychologicalGuardrail = behavior != null
                && behavior.getHypocrisyScore() != null
                && behavior.getHypocrisyScore() > 60
                && article.getSentiment() == NewsArticle.Sentiment.NEGATIVE
                && article.getRelevanceLevel() == NewsArticle.RelevanceLevel.HIGH;

        // ── Final score ───────────────────────────────────────────────────────
        double rawScore = (base
                + portfolioWeightBoost
                + correlationBoost
                + goalAlignmentBoost
                + marketModifier
                + taxBoost)
                * behaviorFactor;

        int personalizedScore = (int) Math.clamp(Math.round(rawScore), 0, 100);

        return new PersonalizedNewsItem(
                article,
                personalizedScore,
                base,
                portfolioWeightBoost,
                correlationBoost,
                goalAlignmentBoost,
                behaviorFactor,
                marketModifier,
                taxBoost,
                primaryMatchSymbol,
                primaryCorrelationReason,
                Collections.unmodifiableList(alignedGoalNames),
                actionType,
                psychologicalGuardrail,
                anomalyAlert
        );
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pillar 2 — Goal alignment
    // ══════════════════════════════════════════════════════════════════════════

    private int computeGoalAlignment(NewsArticle article, FinancialGoal goal) {
        if (goal.getCategory() == null) return 0;
        int boost = 0;

        // ImpactHorizon × GoalCategory alignment
        if (article.getImpactHorizon() != null) {
            boost += switch (article.getImpactHorizon()) {
                case SHORT_TERM -> switch (goal.getCategory()) {
                    case SHORT_TERM  -> 10;
                    case MEDIUM_TERM ->  5;
                    case LONG_TERM   ->  2;
                };
                case MEDIUM_TERM -> switch (goal.getCategory()) {
                    case SHORT_TERM  ->  3;
                    case MEDIUM_TERM -> 10;
                    case LONG_TERM   ->  7;
                };
                case LONG_TERM -> switch (goal.getCategory()) {
                    case SHORT_TERM  ->  1;
                    case MEDIUM_TERM ->  5;
                    case LONG_TERM   -> 10;
                };
            };
        }

        // GoalType × article.category bonus
        if (article.getCategory() != null && goal.getType() != null) {
            boost += goalCategoryBonus(goal.getType(), article.getCategory());
        }

        // AT_RISK or OFF_TRACK goals: amplify by 1.5×
        if (goal.getStatus() == FinancialGoal.GoalStatus.AT_RISK
                || goal.getStatus() == FinancialGoal.GoalStatus.OFF_TRACK) {
            boost = (int) (boost * 1.5);
        }

        // CRITICAL priority: flat +3
        if (goal.getPriority() == FinancialGoal.GoalPriority.CRITICAL) {
            boost += 3;
        }

        return boost;
    }

    private int goalCategoryBonus(FinancialGoal.GoalType goalType, NewsArticle.Category articleCategory) {
        return switch (goalType) {
            case RETIREMENT     -> switch (articleCategory) {
                case POLICY, MACRO_RISK -> 5;
                case GEOPOLITICAL       -> 3;
                default                 -> 0;
            };
            case WEALTH_BUILDING -> switch (articleCategory) {
                case EARNINGS, IPO, CORPORATE_ACTION -> 5;
                case ANALYST_RECOMMENDATION          -> 3;
                default                              -> 0;
            };
            case DEBT_PAYOFF    -> switch (articleCategory) {
                case POLICY -> 3;
                default     -> 0;
            };
            case INVESTMENT     -> switch (articleCategory) {
                case EARNINGS, IPO, ANALYST_RECOMMENDATION, CORPORATE_ACTION -> 5;
                case POLICY                                                   -> 2;
                default                                                       -> 0;
            };
            case EMERGENCY_FUND -> switch (articleCategory) {
                case MACRO_RISK, GEOPOLITICAL -> 4;
                default                       -> 0;
            };
            case PROPERTY       -> switch (articleCategory) {
                case POLICY -> 3;
                default     -> 0;
            };
            case SAVINGS        -> switch (articleCategory) {
                case MACRO_RISK -> 2;
                case POLICY     -> 1;
                default         -> 0;
            };
            default -> 0;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pillar 3 — Behavior alignment (skeleton; Phase 4 fills in)
    // ══════════════════════════════════════════════════════════════════════════

    private double computeBehaviorFactor(NewsArticle article, InvestorBehaviorProfile behavior) {
        if (behavior == null || behavior.getTradingStyle() == null) return 1.0;

        double factor = 1.0;

        // ImpactHorizon vs trading style
        if (article.getImpactHorizon() == NewsArticle.ImpactHorizon.SHORT_TERM) {
            factor += switch (behavior.getTradingStyle()) {
                case SCALPER           ->  0.3;
                case ACTIVE_TRADER     ->  0.1;
                case LONG_TERM_HOLDER  -> -0.3;
            };
        } else if (article.getImpactHorizon() == NewsArticle.ImpactHorizon.LONG_TERM) {
            double patience = behavior.getPatienceIndex() != null ? behavior.getPatienceIndex() : 0.5;
            if (patience > 0.7) factor += 0.2;
            if (behavior.getTradingStyle() == InvestorBehaviorProfile.TradingStyle.SCALPER) factor -= 0.2;
        }

        // Panic-prone investor: show HIGH NEGATIVE articles more prominently
        Double panicRatio = behavior.getPanicSellRatio();
        if (panicRatio != null && panicRatio > 0.3
                && article.getSentiment() == NewsArticle.Sentiment.NEGATIVE
                && article.getRelevanceLevel() == NewsArticle.RelevanceLevel.HIGH) {
            factor += 0.2;
        }

        return Math.clamp(factor, 0.5, 1.5);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Pillar 4 — Market condition modifier (skeleton; Phase 7 fills in)
    // ══════════════════════════════════════════════════════════════════════════

    private int computeMarketModifier(NewsArticle article,
                                       MarketContextService.MarketContextSnapshot ctx) {
        int modifier = 0;

        // Volatile market + HIGH article → +8
        if (ctx.isVolatileMarket() && article.getRelevanceLevel() == NewsArticle.RelevanceLevel.HIGH) {
            modifier += 8;
        }

        // Portfolio declining: NEGATIVE articles matter more
        if (ctx.portfolioTrend() < -2.0 && article.getSentiment() == NewsArticle.Sentiment.NEGATIVE) {
            modifier += 5;
        }

        // Sector mood alignment (Phase 7: sector moods will be populated)
        String primarySector = parseCSV(article.getRelatedSectors()).stream().findFirst().orElse(null);
        if (primarySector != null) {
            String sectorMood = ctx.sectorMoods().get(primarySector);
            if ("BEARISH".equals(sectorMood)) {
                modifier += article.getSentiment() == NewsArticle.Sentiment.NEGATIVE ? 5 : -3;
            } else if ("BULLISH".equals(sectorMood) && article.getSentiment() == NewsArticle.Sentiment.POSITIVE) {
                modifier += 3;
            }
        }

        return Math.clamp(modifier, -10, 10);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ActionType assignment
    // ══════════════════════════════════════════════════════════════════════════

    private ActionType assignActionType(NewsArticle article, List<String> articleSymbols,
                                         Set<String> heldSymbols, List<Investment> investments,
                                         Map<String, Double> sectorWeights) {
        // Corporate action for held stock — highest priority
        if (article.getCategory() == NewsArticle.Category.CORPORATE_ACTION
                && articleSymbols.stream().anyMatch(heldSymbols::contains)) {
            return ActionType.CORPORATE_ACTION_REQUIRED;
        }

        // Tax action: March + LTCG/STCG content
        if (LocalDate.now().getMonth() == Month.MARCH) {
            String titleLower = article.getTitle().toLowerCase();
            if (titleLower.contains("ltcg") || titleLower.contains("stcg")
                    || (titleLower.contains("tax") && titleLower.contains("capital"))) {
                return ActionType.TAX_ACTION;
            }
        }

        // BUY_DIP: NEGATIVE market sentiment but POSITIVE fundamental sentiment,
        // short-term impact only, and article is about a held stock
        if (article.getSentiment() == NewsArticle.Sentiment.NEGATIVE
                && article.getFundamentalSentiment() == NewsArticle.Sentiment.POSITIVE
                && article.getImpactHorizon() == NewsArticle.ImpactHorizon.SHORT_TERM
                && articleSymbols.stream().anyMatch(heldSymbols::contains)) {
            return ActionType.BUY_DIP;
        }

        // REBALANCE: any sector in portfolio exceeds 40% and article is negative for that sector
        for (Map.Entry<String, Double> entry : sectorWeights.entrySet()) {
            if (entry.getValue() > 0.40 && article.getSentiment() == NewsArticle.Sentiment.NEGATIVE) {
                List<String> articleSectors = parseCSV(article.getRelatedSectors());
                if (articleSectors.contains(entry.getKey())) {
                    return ActionType.REBALANCE;
                }
            }
        }

        return ActionType.WATCH;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Volatility Normalizer
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * In a blood-red market, everything scores 90+, causing notification fatigue.
     * Re-scales so only the top 20% of articles remain HIGH relevance.
     */
    private List<PersonalizedNewsItem> applyVolatilityNormalizer(List<PersonalizedNewsItem> items) {
        if (items.size() < 5) return items; // not enough articles to normalize

        List<Integer> scores = items.stream()
                .map(PersonalizedNewsItem::personalizedScore)
                .sorted()
                .toList();

        int p80Index = (int) (scores.size() * 0.8);
        double p80 = scores.get(Math.min(p80Index, scores.size() - 1));
        if (p80 <= 0) return items;

        return items.stream().map(item -> {
            int normalized = (int) Math.clamp(
                    Math.round((item.personalizedScore() / p80) * 70), 0, 100);
            // Only normalize items above the 70 threshold to avoid compressing low scores
            int finalScore = item.personalizedScore() > 70 ? normalized : item.personalizedScore();
            return new PersonalizedNewsItem(
                    item.article(), finalScore, item.baseRelevanceScore(),
                    item.portfolioWeightBoost(), item.correlationBoost(),
                    item.goalAlignmentBoost(), item.behaviorAlignmentFactor(),
                    item.marketConditionModifier(), item.taxHarvestBoost(),
                    item.primaryMatchSymbol(), item.primaryCorrelationReason(),
                    item.alignedGoalNames(), item.actionType(),
                    item.psychologicalGuardrail(), item.anomalyAlert()
            );
        }).toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Double> buildSymbolWeights(List<Investment> investments, BigDecimal totalCost) {
        if (investments.isEmpty() || totalCost.compareTo(BigDecimal.ZERO) == 0) return Map.of();
        double total = totalCost.doubleValue();
        Map<String, Double> weights = new HashMap<>();
        for (Investment inv : investments) {
            if (inv.getSymbol() == null || inv.getSymbol().isBlank()) continue;
            double weight = inv.getTotalCost() != null
                    ? inv.getTotalCost().doubleValue() / total : 0;
            weights.merge(inv.getSymbol().toUpperCase(), weight, Double::sum);
        }
        return weights;
    }

    private Map<String, Double> buildSectorWeights(List<Investment> investments, BigDecimal totalCost) {
        if (investments.isEmpty() || totalCost.compareTo(BigDecimal.ZERO) == 0) return Map.of();
        double total = totalCost.doubleValue();
        Map<String, Double> weights = new HashMap<>();
        for (Investment inv : investments) {
            if (inv.getSector() == null || inv.getSector().isBlank()) continue;
            double weight = inv.getTotalCost() != null
                    ? inv.getTotalCost().doubleValue() / total : 0;
            weights.merge(inv.getSector(), weight, Double::sum);
        }
        return weights;
    }

    private List<String> parseCSV(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    private String buildCorrelationReason(String articleTitle, String sector, double impact) {
        // Derive the macro trigger from the title (first 40 chars)
        String shortTitle = articleTitle != null && articleTitle.length() > 40
                ? articleTitle.substring(0, 40) + "…"
                : articleTitle;
        String direction = impact > 0 ? "→ positive for" : "→ negative for";
        return shortTitle + " " + direction + " " + sector;
    }
}
