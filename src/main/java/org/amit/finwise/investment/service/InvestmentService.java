package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.repository.PortfolioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvestmentService {

    private final InvestmentRepository investmentRepository;
    private final PortfolioRepository portfolioRepository;

    @Transactional
    public Investment addInvestment(String userId, InvestmentType type, String symbol,
                                    String name, LocalDate purchaseDate, BigDecimal quantity,
                                    BigDecimal costPerUnit, String platform) {
        BigDecimal totalCost = quantity.multiply(costPerUnit);
        Investment investment = Investment.builder()
                .userId(userId).type(type).symbol(symbol).name(name)
                .purchaseDate(purchaseDate).quantity(quantity).costPerUnit(costPerUnit)
                .totalCost(totalCost).currentPrice(costPerUnit).currentValue(totalCost)
                .platform(platform).unrealizedGainLoss(BigDecimal.ZERO).gainLossPercentage(BigDecimal.ZERO)
                .build();
        log.info("Added investment: {} - {} units at {}", name, quantity, costPerUnit);
        return investmentRepository.save(investment);
    }

    @Transactional
    public Investment updateInvestmentPrice(Long investmentId, BigDecimal currentPrice) {
        Investment investment = investmentRepository.findById(investmentId)
                .orElseThrow(() -> new RuntimeException("Investment not found"));
        BigDecimal newValue = investment.getQuantity().multiply(currentPrice);
        BigDecimal gainLoss = newValue.subtract(investment.getTotalCost());
        BigDecimal gainLossPercent = gainLoss.divide(investment.getTotalCost(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        investment.setCurrentPrice(currentPrice);
        investment.setCurrentValue(newValue);
        investment.setUnrealizedGainLoss(gainLoss);
        investment.setGainLossPercentage(gainLossPercent);
        log.info("Updated {} price to {}, gain/loss: {}", investment.getSymbol(), currentPrice, gainLoss);
        return investmentRepository.save(investment);
    }

    public PortfolioAnalysis getPortfolioAnalysis(String userId) {
        List<Investment> active = investmentRepository.findActiveInvestments(userId);
        PortfolioAnalysis analysis = new PortfolioAnalysis();
        analysis.totalCost = investmentRepository.totalInvestmentCost(userId);
        analysis.currentValue = investmentRepository.totalPortfolioValue(userId);
        analysis.unrealizedGains = investmentRepository.totalUnrealizedGains(userId);
        if (analysis.totalCost.compareTo(BigDecimal.ZERO) > 0) {
            analysis.gainLossPercentage = analysis.unrealizedGains
                    .divide(analysis.totalCost, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
        analysis.totalHoldings = active.size();
        analysis.assetAllocation = calculateAssetAllocation(active);
        analysis.sectorAllocation = calculateSectorAllocation(active);
        analysis.diversificationScore = calculateDiversificationScore(analysis);
        analysis.topPerformers = getTopPerformers(active, 5);
        analysis.underperformers = getUnderperformers(active, 5);
        return analysis;
    }

    private Map<String, BigDecimal> calculateAssetAllocation(List<Investment> investments) {
        Map<InvestmentType, BigDecimal> allocation = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Investment inv : investments) {
            total = total.add(inv.getCurrentValue());
            allocation.merge(inv.getType(), inv.getCurrentValue(), BigDecimal::add);
        }
        Map<String, BigDecimal> result = new HashMap<>();
        final BigDecimal finalTotal = total;
        allocation.forEach((k, v) -> result.put(k.toString(),
                v.divide(finalTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
        return result;
    }

    private Map<String, BigDecimal> calculateSectorAllocation(List<Investment> investments) {
        Map<String, BigDecimal> allocation = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Investment inv : investments) {
            if (inv.getSector() != null) {
                total = total.add(inv.getCurrentValue());
                allocation.merge(inv.getSector(), inv.getCurrentValue(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> result = new HashMap<>();
        final BigDecimal finalTotal = total;
        if (finalTotal.compareTo(BigDecimal.ZERO) > 0) {
            allocation.forEach((k, v) -> result.put(k,
                    v.divide(finalTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
        }
        return result;
    }

    private Integer calculateDiversificationScore(PortfolioAnalysis analysis) {
        int score = Math.min(30, analysis.totalHoldings * 3);
        score += Math.min(35, analysis.assetAllocation.size() * 10);
        boolean concentrated = analysis.assetAllocation.values().stream()
                .anyMatch(v -> v.compareTo(BigDecimal.valueOf(30)) > 0);
        if (!concentrated) score += 20;
        score += Math.min(15, analysis.sectorAllocation.size() * 3);
        return Math.min(100, score);
    }

    private List<InvestmentPerformance> getTopPerformers(List<Investment> investments, int limit) {
        return investments.stream()
                .map(inv -> new InvestmentPerformance(inv.getSymbol(), inv.getName(),
                        inv.getGainLossPercentage(), inv.getUnrealizedGainLoss()))
                .sorted(Comparator.comparing((InvestmentPerformance p) -> p.gainLossPercentage).reversed())
                .limit(limit).toList();
    }

    private List<InvestmentPerformance> getUnderperformers(List<Investment> investments, int limit) {
        return investments.stream()
                .map(inv -> new InvestmentPerformance(inv.getSymbol(), inv.getName(),
                        inv.getGainLossPercentage(), inv.getUnrealizedGainLoss()))
                .sorted(Comparator.comparing((InvestmentPerformance p) -> p.gainLossPercentage))
                .limit(limit).toList();
    }

    public RebalancingRecommendation getRebalancingRecommendation(String userId, Map<String, BigDecimal> targetAllocation) {
        PortfolioAnalysis analysis = getPortfolioAnalysis(userId);
        RebalancingRecommendation rec = new RebalancingRecommendation();
        rec.currentAllocation = analysis.assetAllocation;
        rec.targetAllocation = targetAllocation;
        rec.rebalancingNeeded = targetAllocation.entrySet().stream().anyMatch(e -> {
            BigDecimal current = analysis.assetAllocation.getOrDefault(e.getKey(), BigDecimal.ZERO);
            return e.getValue().subtract(current).abs().compareTo(BigDecimal.valueOf(5)) > 0;
        });
        rec.lastRebalanceDate = LocalDate.now();
        rec.nextRecommendedDate = LocalDate.now().plusMonths(6);
        return rec;
    }

    public static class PortfolioAnalysis {
        public BigDecimal totalCost;
        public BigDecimal currentValue;
        public BigDecimal unrealizedGains;
        public BigDecimal gainLossPercentage;
        public Integer totalHoldings;
        public Map<String, BigDecimal> assetAllocation;
        public Map<String, BigDecimal> sectorAllocation;
        public Integer diversificationScore;
        public List<InvestmentPerformance> topPerformers;
        public List<InvestmentPerformance> underperformers;
    }

    public static class InvestmentPerformance {
        public String symbol;
        public String name;
        public BigDecimal gainLossPercentage;
        public BigDecimal gainLossAmount;
        public InvestmentPerformance(String symbol, String name, BigDecimal gainLossPercentage, BigDecimal gainLossAmount) {
            this.symbol = symbol; this.name = name;
            this.gainLossPercentage = gainLossPercentage; this.gainLossAmount = gainLossAmount;
        }
    }

    public static class RebalancingRecommendation {
        public Map<String, BigDecimal> currentAllocation;
        public Map<String, BigDecimal> targetAllocation;
        public Boolean rebalancingNeeded;
        public LocalDate lastRebalanceDate;
        public LocalDate nextRecommendedDate;
    }
}
