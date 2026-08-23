package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
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
    private final PortfolioRiskService portfolioRiskService;
    private final CapitalGainsTaxService capitalGainsTaxService;
    private final BondAnalyticsService bondAnalyticsService;

    /**
     * Fixed-coupon bond analytics (BPR-7) for a {@code BOND} holding. The {@link Investment}
     * entity stores only price/quantity, so the bond terms (coupon, maturity, frequency,
     * face) must be supplied here — typically user-entered or sourced from the instrument
     * master. Returns YTM, durations, convexity, DV01 and accrued interest; empty when the
     * holding is not a bond or the terms are degenerate.
     */
    public Optional<BondAnalyticsService.BondAnalytics> analyzeBond(
            Investment bond, double faceValue, double annualCouponRate, int frequency,
            LocalDate settlement, LocalDate maturity, BondAnalyticsService.DayCount dayCount) {
        if (bond == null || bond.getType() != InvestmentType.BOND
                || bond.getCurrentPrice() == null) {
            return Optional.empty();
        }
        return bondAnalyticsService.analyze(new BondAnalyticsService.BondSpec(
                faceValue, annualCouponRate, frequency, settlement, maturity,
                bond.getCurrentPrice().doubleValue(), dayCount));
    }

    @Transactional
    public Investment addInvestment(String userId, InvestmentType type, String symbol,
                                    String name, LocalDate purchaseDate, BigDecimal quantity,
                                    BigDecimal costPerUnit, String platform,
                                    BigDecimal interestRate, LocalDate maturityDate,
                                    BigDecimal sumAssured, BigDecimal annualPremium) {
        BigDecimal totalCost = quantity.multiply(costPerUnit);
        Investment investment = Investment.builder()
                .userId(userId).type(type).symbol(symbol).name(name)
                .purchaseDate(purchaseDate).quantity(quantity).costPerUnit(costPerUnit)
                .totalCost(totalCost).currentPrice(costPerUnit).currentValue(totalCost)
                .platform(platform).unrealizedGainLoss(BigDecimal.ZERO).gainLossPercentage(BigDecimal.ZERO)
                .interestRate(interestRate).maturityDate(maturityDate)
                .sumAssured(sumAssured).annualPremium(annualPremium)
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
        analysis.diversificationScore = calculateDiversificationScore(userId, analysis);
        analysis.topPerformers = getTopPerformers(active, 5);
        analysis.underperformers = getUnderperformers(active, 5);

        // Tax-adjusted view: pre-tax P&L overstates wealth, esp. for STCG holdings
        CapitalGainsTaxService.TaxEstimate tax = capitalGainsTaxService.estimate(active);
        analysis.estimatedTaxIfSoldToday = tax.totalTaxIfSoldToday();
        analysis.netGainsAfterTax = analysis.unrealizedGains.subtract(tax.totalTaxIfSoldToday());
        analysis.taxBreakdown = tax;
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

    /**
     * Correlation-aware diversification score from the risk engine's ENB and HHI
     * (ten perfectly correlated stocks score poorly here, unlike count-based
     * heuristics). Falls back to the legacy count heuristic when there isn't
     * enough price history to compute a covariance matrix.
     */
    private Integer calculateDiversificationScore(String userId, PortfolioAnalysis analysis) {
        try {
            Optional<RiskDecomposition> rd = portfolioRiskService.compute(userId);
            if (rd.isPresent() && !rd.get().isLowConfidence()) {
                double enb = rd.get().effectiveNumberOfBets();
                double sectorHHI = rd.get().sectorHHI();
                // ENB of 8+ independent bets ≈ fully diversified for a retail portfolio
                double enbScore = Math.min(1.0, enb / 8.0) * 70;
                // sectorHHI 1.0 (single sector) → 0; 0.125 (8 equal sectors) → full marks
                double sectorScore = Math.min(1.0, (1.0 - sectorHHI) / 0.875) * 30;
                return (int) Math.round(Math.min(100, enbScore + sectorScore));
            }
        } catch (Exception e) {
            log.debug("Risk-engine diversification unavailable, using heuristic: {}", e.getMessage());
        }
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

    /** Drift beyond which an asset class triggers a rebalance. */
    private static final BigDecimal REBALANCE_TRIGGER_PCT = BigDecimal.valueOf(5);
    /** Once triggered, rebalance back to within this band of target (not exactly to target) to cut churn. */
    private static final BigDecimal REBALANCE_BAND_PCT = BigDecimal.valueOf(2);

    /**
     * Tolerance-band rebalancing with concrete ₹ actions. An asset class is
     * actionable only when its drift exceeds {@link #REBALANCE_TRIGGER_PCT};
     * the suggested trade brings it back to target ± {@link #REBALANCE_BAND_PCT},
     * not exactly to target, to avoid churning on noise. Sell actions carry the
     * portfolio-level capital-gains estimate as a cost warning.
     */
    public RebalancingRecommendation getRebalancingRecommendation(String userId, Map<String, BigDecimal> targetAllocation) {
        PortfolioAnalysis analysis = getPortfolioAnalysis(userId);
        RebalancingRecommendation rec = new RebalancingRecommendation();
        rec.currentAllocation = analysis.assetAllocation;
        rec.targetAllocation = targetAllocation;
        rec.actions = new ArrayList<>();

        BigDecimal totalValue = analysis.currentValue != null ? analysis.currentValue : BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : targetAllocation.entrySet()) {
            BigDecimal current = analysis.assetAllocation.getOrDefault(e.getKey(), BigDecimal.ZERO);
            BigDecimal drift = current.subtract(e.getValue()); // positive = overweight
            if (drift.abs().compareTo(REBALANCE_TRIGGER_PCT) <= 0) continue;

            // Trade back to the edge of the band nearest current, not all the way to target
            BigDecimal correction = drift.abs().subtract(REBALANCE_BAND_PCT);
            BigDecimal amount = totalValue.multiply(correction)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

            RebalancingAction action = new RebalancingAction();
            action.assetClass = e.getKey();
            action.currentPercent = current;
            action.targetPercent = e.getValue();
            action.driftPercent = drift;
            action.action = drift.signum() > 0 ? "SELL" : "BUY";
            action.amount = amount;
            if (drift.signum() > 0) {
                action.costNote = "Selling may trigger capital gains tax (portfolio-wide estimate if fully "
                        + "liquidated: ₹" + analysis.estimatedTaxIfSoldToday
                        + ") plus STT/brokerage. Prefer directing fresh inflows to underweight classes first.";
            }
            rec.actions.add(action);
        }

        rec.rebalancingNeeded = !rec.actions.isEmpty();
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
        public BigDecimal estimatedTaxIfSoldToday;   // STCG + LTCG on unrealized gains
        public BigDecimal netGainsAfterTax;          // unrealizedGains − estimated tax
        public CapitalGainsTaxService.TaxEstimate taxBreakdown;
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
        public List<RebalancingAction> actions;
        public LocalDate lastRebalanceDate;
        public LocalDate nextRecommendedDate;
    }

    public static class RebalancingAction {
        public String assetClass;
        public BigDecimal currentPercent;
        public BigDecimal targetPercent;
        public BigDecimal driftPercent;   // positive = overweight
        public String action;             // BUY | SELL
        public BigDecimal amount;         // ₹ to trade (to target ± band)
        public String costNote;           // tax/cost warning for sells
    }
}
