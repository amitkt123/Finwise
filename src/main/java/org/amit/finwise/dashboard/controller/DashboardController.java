package org.amit.finwise.dashboard.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.cfo.service.analytics.MoneyWeightedReturnService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.GoalAnalyzerService;
import org.amit.finwise.investment.service.InvestmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class DashboardController {

    private final InvestmentService investmentService;
    private final BudgetMonitorService budgetMonitorService;
    private final GoalAnalyzerService goalAnalyzerService;
    private final FinancialGoalRepository goalRepository;
    private final PortfolioPerformanceService portfolioPerformanceService;
    private final MoneyWeightedReturnService moneyWeightedReturnService;
    private final PortfolioRiskService portfolioRiskService;

    @GetMapping("/dashboard")
    public ResponseEntity<FinancialDashboard> getFinancialDashboard(@RequestParam String userId) {
        InvestmentService.PortfolioAnalysis portfolio = investmentService.getPortfolioAnalysis(userId);
        BudgetMonitorService.BudgetStatus budget = budgetMonitorService.getCurrentMonthBudgetStatus(userId);
        String currentMonth = LocalDate.now().toString().substring(0, 7);

        FinancialDashboard dashboard = new FinancialDashboard();
        dashboard.portfolioValue = portfolio.currentValue;
        dashboard.portfolioGains = portfolio.unrealizedGains;
        dashboard.portfolioGainPercent = portfolio.gainLossPercentage;
        dashboard.monthlyBudget = budget.totalBudget;
        dashboard.monthlySpent = budget.totalSpent;
        dashboard.monthlyPercentSpent = budget.totalPercentSpent;
        dashboard.activeGoals = goalRepository.findActiveGoals(userId).size();
        dashboard.atRiskGoals = goalRepository.findAtRiskGoals(userId).size();
        dashboard.budgetAlerts = budgetMonitorService.getBudgetAlerts(userId, currentMonth).size();
        dashboard.goalAlerts = goalAnalyzerService.getGoalAlerts(userId).size();

        // Performance: time-weighted (skill, SEBI-grade) and money-weighted (XIRR, what
        // the investor's own rupees earned). Both shown so the user can read timing vs.
        // selection. NaN/empty leaves the field null rather than fabricating a number.
        portfolioPerformanceService.computeTwrr(userId).ifPresent(twrr -> {
            if (!Double.isNaN(twrr.annualizedTwrr()))
                dashboard.annualizedTwrrPercent =
                        BigDecimal.valueOf(twrr.annualizedTwrr() * 100).setScale(2, java.math.RoundingMode.HALF_UP);
        });
        moneyWeightedReturnService.computeXirr(userId).ifPresent(x -> {
            if (!Double.isNaN(x.xirr()))
                dashboard.xirrPercent =
                        BigDecimal.valueOf(x.xirr() * 100).setScale(2, java.math.RoundingMode.HALF_UP);
            if (!Double.isNaN(x.absoluteMwr()))
                dashboard.absoluteReturnPercent =
                        BigDecimal.valueOf(x.absoluteMwr() * 100).setScale(2, java.math.RoundingMode.HALF_UP);
        });
        portfolioRiskService.compute(userId).ifPresent(rd -> {
            if (!Double.isNaN(rd.maxDrawdown()) && rd.maxDrawdown() < 0)
                dashboard.maxDrawdownPercent =
                        BigDecimal.valueOf(rd.maxDrawdown() * 100).setScale(2, java.math.RoundingMode.HALF_UP);
            if (!Double.isNaN(rd.calmarRatio()))
                dashboard.calmarRatio =
                        BigDecimal.valueOf(rd.calmarRatio()).setScale(2, java.math.RoundingMode.HALF_UP);
        });

        return ResponseEntity.ok(dashboard);
    }

    public static class FinancialDashboard {
        public BigDecimal portfolioValue;
        public BigDecimal portfolioGains;
        public BigDecimal portfolioGainPercent;
        public BigDecimal monthlyBudget;
        public BigDecimal monthlySpent;
        public BigDecimal monthlyPercentSpent;
        public Integer activeGoals;
        public Integer atRiskGoals;
        public Integer budgetAlerts;
        public Integer goalAlerts;
        /** Annualized time-weighted return % (skill, SEBI-grade); null when too few snapshots. */
        public BigDecimal annualizedTwrrPercent;
        /** Annualized money-weighted return % (XIRR); null when insufficient cash flows. */
        public BigDecimal xirrPercent;
        /** Absolute money-weighted return % on deployed capital; null when unavailable. */
        public BigDecimal absoluteReturnPercent;
        /** Worst peak-to-trough on the portfolio value path %, negative; null when unavailable. */
        public BigDecimal maxDrawdownPercent;
        /** Annualized return ÷ |max drawdown|; null when drawdown is zero/unavailable. */
        public BigDecimal calmarRatio;
    }
}
