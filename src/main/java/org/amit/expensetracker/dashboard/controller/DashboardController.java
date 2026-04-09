package org.amit.expensetracker.dashboard.controller;

import lombok.RequiredArgsConstructor;
import org.amit.expensetracker.budget.service.BudgetMonitorService;
import org.amit.expensetracker.goal.repository.FinancialGoalRepository;
import org.amit.expensetracker.goal.service.GoalAnalyzerService;
import org.amit.expensetracker.investment.service.InvestmentService;
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
    }
}
