package org.amit.expensetracker.cfo.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.expensetracker.cfo.model.InvestorBehaviorProfile;
import org.amit.expensetracker.cfo.model.RiskQuestionnaire;
import org.amit.expensetracker.cfo.model.Transaction;
import org.amit.expensetracker.cfo.model.UserProfile;
import org.amit.expensetracker.cfo.repository.InvestorBehaviorProfileRepository;
import org.amit.expensetracker.cfo.repository.NewsArticleRepository;
import org.amit.expensetracker.cfo.repository.RiskQuestionnaireRepository;
import org.amit.expensetracker.cfo.repository.TransactionRepository;
import org.amit.expensetracker.investment.model.Investment;
import org.amit.expensetracker.investment.repository.InvestmentRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Derives and caches a behavioral investment profile from transaction history.
 *
 * Phase 4 algorithms:
 *   - FIFO holding duration + patience index
 *   - Panic sell detection (HIGH NEGATIVE news within 5 days of a SELL)
 *   - Concentration HHI
 *   - Sector affinity
 *   - Win rate (FIFO cost vs sell price)
 *   - Derived risk appetite
 *
 * Phase 5: hypocrisyScore computed from stated (questionnaire) vs derived risk.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestorBehaviorService {

    private final InvestorBehaviorProfileRepository behaviorRepo;
    private final RiskQuestionnaireRepository questionnaireRepo;
    private final InvestmentRepository investmentRepo;
    private final TransactionRepository transactionRepo;
    private final NewsArticleRepository newsRepo;
    private final ObjectMapper objectMapper;

    private static final int STALE_HOURS = 24;
    private static final int PANIC_WINDOW_DAYS = 5;  // days before SELL to check for panic news

    // ── Public API ────────────────────────────────────────────────────────────

    public Optional<InvestorBehaviorProfile> getProfile(String userId) {
        return behaviorRepo.findByUserId(userId).map(profile -> {
            if (isStale(profile)) {
                log.debug("[Behavior] Profile stale for user={}, recomputing", userId);
                return recompute(userId);
            }
            return profile;
        }).or(() -> {
            log.debug("[Behavior] No profile for user={}, computing fresh", userId);
            return Optional.of(recompute(userId));
        });
    }

    @Transactional
    public InvestorBehaviorProfile recompute(String userId) {
        log.info("[Behavior] Recomputing behavioral profile for user={}", userId);

        List<Investment> activeInvestments = investmentRepo.findActiveInvestments(userId);
        List<Transaction> allBuySell = transactionRepo.findBuySellTransactionsAsc(userId);
        List<Transaction> recentTxns = transactionRepo.findRecentTransactions(
                userId, LocalDate.now().minusYears(1));

        if (allBuySell.isEmpty() && activeInvestments.isEmpty()) {
            log.debug("[Behavior] No data for user={}, returning skeleton", userId);
            return persistSkeleton(userId);
        }

        // ── Trade frequency → trading style ──────────────────────────────────
        long tradeCount12m = recentTxns.stream()
                .filter(t -> t.getTransactionType() == Transaction.TransactionType.BUY
                          || t.getTransactionType() == Transaction.TransactionType.SELL)
                .count();
        double tradesPerMonth = tradeCount12m / 12.0;

        InvestorBehaviorProfile.TradingStyle tradingStyle;
        if (tradesPerMonth >= 10)      tradingStyle = InvestorBehaviorProfile.TradingStyle.SCALPER;
        else if (tradesPerMonth >= 3)  tradingStyle = InvestorBehaviorProfile.TradingStyle.ACTIVE_TRADER;
        else                           tradingStyle = InvestorBehaviorProfile.TradingStyle.LONG_TERM_HOLDER;

        // ── FIFO analysis (holding duration + win rate) ───────────────────────
        FifoResult fifo = computeFifo(allBuySell);

        double avgHoldingDays = fifo.holdingDaysList.isEmpty() ? 0.0
                : fifo.holdingDaysList.stream().mapToLong(Long::longValue).average().orElse(0);
        double patienceIndex = Math.min(avgHoldingDays / 365.0, 1.0);

        double winRate = (fifo.totalClosed == 0) ? 0.0
                : (double) fifo.winCount / fifo.totalClosed;
        int closedPositions = fifo.totalClosed;

        // ── Panic sell detection ──────────────────────────────────────────────
        List<Transaction> sellTxns = allBuySell.stream()
                .filter(t -> t.getTransactionType() == Transaction.TransactionType.SELL)
                .toList();
        int totalSells = sellTxns.size();
        int panicSells = 0;
        for (Transaction sell : sellTxns) {
            if (sell.getSymbol() == null) continue;
            LocalDate sellDate = sell.getTransactionDate();
            LocalDate windowStart = sellDate.minusDays(PANIC_WINDOW_DAYS);
            List<?> panicNews = newsRepo.findNegativeHighRelevanceForSymbol(
                    sell.getSymbol(), windowStart, sellDate);
            if (!panicNews.isEmpty()) panicSells++;
        }
        double panicSellRatio = (totalSells == 0) ? 0.0 : (double) panicSells / totalSells;

        // ── Concentration HHI ─────────────────────────────────────────────────
        BigDecimal totalCost = investmentRepo.totalInvestmentCost(userId);
        double hhi = 0.0;
        int activeSymbolCount = 0;
        if (totalCost.compareTo(BigDecimal.ZERO) > 0) {
            double totalD = totalCost.doubleValue();
            for (Investment inv : activeInvestments) {
                if (inv.getTotalCost() == null) continue;
                double weight = inv.getTotalCost().doubleValue() / totalD;
                hhi += weight * weight;
            }
            Set<String> symbols = activeInvestments.stream()
                    .filter(i -> i.getSymbol() != null)
                    .map(i -> i.getSymbol().toUpperCase())
                    .collect(Collectors.toSet());
            activeSymbolCount = symbols.size();
        }

        // ── Sector affinity (last 12 months) ─────────────────────────────────
        // Build symbol→sector map from active investments
        Map<String, String> symbolToSector = new HashMap<>();
        for (Investment inv : activeInvestments) {
            if (inv.getSymbol() != null && inv.getSector() != null) {
                symbolToSector.put(inv.getSymbol().toUpperCase(), inv.getSector());
            }
        }
        Map<String, Integer> sectorCounts = new LinkedHashMap<>();
        for (Transaction t : recentTxns) {
            if (t.getSymbol() == null) continue;
            String sector = symbolToSector.get(t.getSymbol().toUpperCase());
            if (sector != null) {
                sectorCounts.merge(sector, 1, Integer::sum);
            }
        }
        String sectorAffinityJson = "{}";
        try {
            // Sort by count descending
            LinkedHashMap<String, Integer> sorted = sectorCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (a, b) -> a, LinkedHashMap::new));
            sectorAffinityJson = objectMapper.writeValueAsString(sorted);
        } catch (Exception e) {
            log.warn("[Behavior] Failed to serialize sectorAffinity: {}", e.getMessage());
        }

        // ── Derived risk appetite ─────────────────────────────────────────────
        int riskScore = 50; // neutral baseline
        riskScore += switch (tradingStyle) {
            case SCALPER           ->  20;
            case ACTIVE_TRADER     ->   5;
            case LONG_TERM_HOLDER  -> -10;
        };
        if (patienceIndex > 0.7) riskScore -= 10;
        else if (patienceIndex < 0.2) riskScore += 10;

        if (hhi > 0.5) riskScore += 10;       // concentrated bets → aggressive
        else if (hhi < 0.1 && hhi > 0) riskScore -= 5; // very diversified → conservative

        if (panicSellRatio > 0.5) riskScore -= 20;  // panic = actually conservative
        else if (panicSellRatio < 0.1 && totalSells > 0) riskScore += 5;

        riskScore = Math.clamp(riskScore, 0, 100);

        UserProfile.RiskAppetite derivedRisk;
        if (riskScore >= 65)       derivedRisk = UserProfile.RiskAppetite.AGGRESSIVE;
        else if (riskScore >= 35)  derivedRisk = UserProfile.RiskAppetite.MODERATE;
        else                       derivedRisk = UserProfile.RiskAppetite.CONSERVATIVE;

        // ── Hypocrisy score (Phase 5) ─────────────────────────────────────────
        int hypocrisyScore = 0;
        Optional<RiskQuestionnaire> questionnaire = questionnaireRepo.findByUserId(userId);
        if (questionnaire.isPresent() && questionnaire.get().getStatedRiskScore() != null) {
            int statedRiskScore = questionnaire.get().getStatedRiskScore();
            hypocrisyScore = computeHypocrisyScore(statedRiskScore, riskScore);
        }

        // ── Persist ───────────────────────────────────────────────────────────
        InvestorBehaviorProfile profile = behaviorRepo.findByUserId(userId)
                .orElse(InvestorBehaviorProfile.builder().userId(userId).build());

        profile.setTradesPerMonth(tradesPerMonth);
        profile.setTradingStyle(tradingStyle);
        profile.setAvgHoldingDurationDays(avgHoldingDays);
        profile.setPatienceIndex(patienceIndex);
        profile.setPanicSellCount(panicSells);
        profile.setPanicSellRatio(panicSellRatio);
        profile.setActiveSymbolCount(activeSymbolCount);
        profile.setConcentrationHHI(hhi);
        profile.setSectorAffinityJson(sectorAffinityJson);
        profile.setWinRate(winRate);
        profile.setClosedPositionsAnalyzed(closedPositions);
        profile.setDerivedRiskAppetite(derivedRisk);
        profile.setHypocrisyScore(hypocrisyScore);
        profile.setComputedAt(LocalDateTime.now());
        profile.setTransactionCount((int) tradeCount12m);

        InvestorBehaviorProfile saved = behaviorRepo.save(profile);
        log.info("[Behavior] Profile saved for user={}: style={}, panicRatio={}, HHI={}, hypocrisy={}",
                userId, tradingStyle,
                String.format("%.2f", panicSellRatio),
                String.format("%.2f", hhi),
                hypocrisyScore);
        return saved;
    }

    @Async
    public void recomputeAsync(String userId) {
        try {
            recompute(userId);
        } catch (Exception e) {
            log.warn("[Behavior] Async recompute failed for user={}: {}", userId, e.getMessage());
        }
    }

    public int computeHypocrisyScore(int statedRiskScore, int derivedRiskScore) {
        int gap = Math.abs(statedRiskScore - derivedRiskScore);
        return Math.min(gap * 2, 100);
    }

    public int scoreQuestionnaire(RiskQuestionnaire q) {
        int score = 50;

        if (q.getDrawdownReaction() != null) {
            score += switch (q.getDrawdownReaction()) {
                case SELL_IMMEDIATELY -> -20;
                case HOLD_AND_WAIT   ->   0;
                case BUY_MORE        ->  20;
            };
        }

        if (q.getMaxAcceptableLossPercent() != null) {
            score += switch (q.getMaxAcceptableLossPercent()) {
                case Integer i when i <= 5  -> -15;
                case Integer i when i <= 10 ->  -5;
                case Integer i when i <= 20 ->  10;
                default                     ->  20;
            };
        }

        if (q.getInvestmentHorizonYears() != null) {
            score += q.getInvestmentHorizonYears() >= 10 ? 10
                   : q.getInvestmentHorizonYears() >= 5  ?  5
                   : -5;
        }

        if (q.getPrimaryGoalFocus() != null) {
            score += switch (q.getPrimaryGoalFocus()) {
                case CAPITAL_PRESERVATION -> -15;
                case REGULAR_INCOME       ->  -5;
                case BALANCED             ->   0;
                case CAPITAL_GROWTH       ->  15;
            };
        }

        if (q.getIncomeStabilityRating() != null) {
            score += (q.getIncomeStabilityRating() - 3) * 3; // -6 to +6
        }

        return Math.clamp(score, 0, 100);
    }

    // ── FIFO computation ──────────────────────────────────────────────────────

    private record FifoResult(List<Long> holdingDaysList, int winCount, int totalClosed) {}

    /**
     * Pairs SELL transactions against BUY lots in FIFO order per symbol.
     * Computes holding duration (days) and win/loss for each closed position.
     */
    private FifoResult computeFifo(List<Transaction> buySellAsc) {
        // Build buy queues per symbol: (date, pricePerUnit, remainingQty)
        Map<String, Deque<double[]>> buyQueues = new HashMap<>(); // [0]=date_epoch, [1]=price, [2]=qty

        for (Transaction t : buySellAsc) {
            if (t.getTransactionType() != Transaction.TransactionType.BUY) continue;
            if (t.getQuantity() == null || t.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
            String sym = t.getSymbol().toUpperCase();
            double price = t.getPricePerUnit() != null ? t.getPricePerUnit().doubleValue()
                    : (t.getAmount() != null && t.getQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? t.getAmount().doubleValue() / t.getQuantity().doubleValue() : 0);
            double[] lot = {
                t.getTransactionDate().toEpochDay(),
                price,
                t.getQuantity().doubleValue()
            };
            buyQueues.computeIfAbsent(sym, k -> new ArrayDeque<>()).add(lot);
        }

        List<Long> holdingDaysList = new ArrayList<>();
        int winCount = 0;
        int totalClosed = 0;

        for (Transaction sell : buySellAsc) {
            if (sell.getTransactionType() != Transaction.TransactionType.SELL) continue;
            if (sell.getQuantity() == null || sell.getQuantity().compareTo(BigDecimal.ZERO) <= 0) continue;
            String sym = sell.getSymbol().toUpperCase();
            Deque<double[]> queue = buyQueues.get(sym);
            if (queue == null || queue.isEmpty()) continue;

            double sellPrice = sell.getPricePerUnit() != null ? sell.getPricePerUnit().doubleValue()
                    : (sell.getAmount() != null && sell.getQuantity().compareTo(BigDecimal.ZERO) > 0
                        ? sell.getAmount().doubleValue() / sell.getQuantity().doubleValue() : 0);
            long sellEpoch = sell.getTransactionDate().toEpochDay();
            double remainQty = sell.getQuantity().doubleValue();
            double weightedCost = 0;
            double weightedDays = 0;
            double matchedQty = 0;

            while (remainQty > 0.001 && !queue.isEmpty()) {
                double[] lot = queue.peek();
                double lotQty = lot[2];
                double matched = Math.min(remainQty, lotQty);

                weightedCost += lot[1] * matched;
                weightedDays += (sellEpoch - lot[0]) * matched;
                matchedQty += matched;
                remainQty -= matched;

                if (matched >= lotQty - 0.001) {
                    queue.poll();
                } else {
                    lot[2] -= matched; // partial consume
                }
            }

            if (matchedQty > 0) {
                long avgHoldDays = (long) (weightedDays / matchedQty);
                holdingDaysList.add(Math.max(0, avgHoldDays));

                double avgBuyPrice = weightedCost / matchedQty;
                if (sellPrice > avgBuyPrice) winCount++;
                totalClosed++;
            }
        }

        return new FifoResult(holdingDaysList, winCount, totalClosed);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isStale(InvestorBehaviorProfile profile) {
        return profile.getComputedAt().isBefore(LocalDateTime.now().minusHours(STALE_HOURS));
    }

    private InvestorBehaviorProfile persistSkeleton(String userId) {
        InvestorBehaviorProfile profile = behaviorRepo.findByUserId(userId)
                .orElse(InvestorBehaviorProfile.builder().userId(userId).build());
        profile.setComputedAt(LocalDateTime.now());
        profile.setTransactionCount(0);
        return behaviorRepo.save(profile);
    }
}
