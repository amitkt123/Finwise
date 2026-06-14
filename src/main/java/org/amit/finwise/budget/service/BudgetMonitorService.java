package org.amit.finwise.budget.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.budget.model.Budget;
import org.amit.finwise.budget.repository.BudgetRepository;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BudgetMonitorService {

    private final BudgetRepository budgetRepository;
    private final ExpenseRepository expenseRepository;

    @Transactional
    public Budget setBudget(String userId, String budgetMonth, Expense.ExpenseCategory category, BigDecimal budgetLimit) {
        Budget budget = budgetRepository.findByUserIdAndBudgetMonthAndCategory(userId, budgetMonth, category)
                .orElse(Budget.builder().userId(userId).budgetMonth(budgetMonth).category(category).build());
        budget.setBudgetLimit(budgetLimit);
        budget.setRemaining(budgetLimit);
        budget.setPercentSpent(BigDecimal.ZERO);
        budget.setIsOverBudget(false);
        budget.setStatus(Budget.BudgetStatus.ON_TRACK);
        log.info("Set budget for {}/{}: {} INR", budgetMonth, category, budgetLimit);
        return budgetRepository.save(budget);
    }

    @Transactional
    public void updateBudgetForExpense(String userId, Expense expense) {
        String budgetMonth = formatMonth(expense.getExpenseDate());
        budgetRepository.findByUserIdAndBudgetMonthAndCategory(userId, budgetMonth, expense.getCategory())
                .ifPresent(budget -> {
                    BigDecimal newSpent = budget.getSpent().add(expense.getAmount());
                    BigDecimal remaining = budget.getBudgetLimit().subtract(newSpent);
                    BigDecimal percent = newSpent.divide(budget.getBudgetLimit(), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    budget.setSpent(newSpent);
                    budget.setRemaining(remaining);
                    budget.setPercentSpent(percent);
                    budget.setIsOverBudget(remaining.compareTo(BigDecimal.ZERO) < 0);
                    if (percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                        budget.setStatus(Budget.BudgetStatus.OVER_BUDGET);
                    } else if (percent.compareTo(budget.getAlertThreshold()) >= 0) {
                        budget.setStatus(Budget.BudgetStatus.WARNING);
                        if (!budget.getAlertSent()) {
                            budget.setAlertSent(true);
                            log.warn("Budget alert for {}/{}: {}% spent", budgetMonth, expense.getCategory(), percent);
                        }
                    } else {
                        budget.setStatus(Budget.BudgetStatus.ON_TRACK);
                    }
                    budgetRepository.save(budget);
                });
    }

    public BudgetStatus getCurrentMonthBudgetStatus(String userId) {
        String currentMonth = formatMonth(LocalDate.now());
        List<Budget> budgets = budgetRepository.findByUserIdAndBudgetMonth(userId, currentMonth);
        BudgetStatus status = new BudgetStatus();
        status.month = currentMonth;
        status.totalBudget = BigDecimal.ZERO;
        status.totalSpent = BigDecimal.ZERO;
        status.categoryStatus = new HashMap<>();
        for (Budget b : budgets) {
            status.totalBudget = status.totalBudget.add(b.getBudgetLimit());
            status.totalSpent = status.totalSpent.add(b.getSpent());
            BudgetCategoryStatus cs = new BudgetCategoryStatus();
            cs.category = b.getCategory();
            cs.budgetLimit = b.getBudgetLimit();
            cs.spent = b.getSpent();
            cs.remaining = b.getRemaining();
            cs.percentSpent = b.getPercentSpent();
            cs.status = b.getStatus();
            status.categoryStatus.put(b.getCategory().toString(), cs);
        }
        if (status.totalBudget.compareTo(BigDecimal.ZERO) > 0) {
            status.totalPercentSpent = status.totalSpent.divide(status.totalBudget, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            status.isOverBudget = status.totalSpent.compareTo(status.totalBudget) > 0;
        }
        return status;
    }

    public List<BudgetAlert> getBudgetAlerts(String userId, String month) {
        return budgetRepository.findBudgetsNeedingAlerts(userId, month).stream().map(b -> {
            BudgetAlert alert = new BudgetAlert();
            alert.category = b.getCategory();
            alert.budgetLimit = b.getBudgetLimit();
            alert.spent = b.getSpent();
            alert.remaining = b.getRemaining();
            alert.percentSpent = b.getPercentSpent();
            alert.status = b.getStatus();
            alert.message = b.getStatus() == Budget.BudgetStatus.OVER_BUDGET
                    ? "Over budget by ₹%s on %s".formatted(b.getSpent().subtract(b.getBudgetLimit()).abs(), b.getCategory())
                    : "Approaching limit for %s: %s%% spent".formatted(b.getCategory(), b.getPercentSpent());
            return alert;
        }).toList();
    }

    public SpendingInsights getSpendingInsights(String userId, int months) {
        SpendingInsights insights = new SpendingInsights();
        insights.averageSpendingByCategory = new HashMap<>();
        insights.trends = new HashMap<>();
        for (int i = 0; i < months; i++) {
            LocalDate date = LocalDate.now().minusMonths(i);
            for (Expense.ExpenseCategory category : Expense.ExpenseCategory.values()) {
                BigDecimal spent = expenseRepository.sumExpensesByMonthAndCategory(userId, date.getYear(), date.getMonthValue(), category);
                if (spent.compareTo(BigDecimal.ZERO) > 0) {
                    insights.trends.computeIfAbsent(category.toString(), _ -> new ArrayList<>()).add(spent);
                }
            }
        }
        insights.trends.forEach((cat, vals) -> {
            BigDecimal avg = vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(vals.size()), 2, RoundingMode.HALF_UP);
            insights.averageSpendingByCategory.put(cat, avg);
        });
        insights.highestSpendingCategories = insights.averageSpendingByCategory.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue())).limit(5).toList();
        insights.potentialSavings = insights.averageSpendingByCategory.entrySet().stream()
                .filter(e -> e.getKey().equals("SUBSCRIPTION") && e.getValue().compareTo(BigDecimal.valueOf(5000)) > 0)
                .map(e -> {
                    SavingsOpportunity opp = new SavingsOpportunity();
                    opp.category = e.getKey();
                    opp.currentSpending = e.getValue();
                    opp.potentialSavings = e.getValue().multiply(BigDecimal.valueOf(0.3));
                    opp.suggestion = "Review subscriptions — consider canceling unused services";
                    return opp;
                }).toList();
        return insights;
    }

    public Map<String, BigDecimal> suggestBudget(String userId, int months) {
        Map<String, BigDecimal> suggestions = new HashMap<>();
        getSpendingInsights(userId, months).averageSpendingByCategory
                .forEach((cat, avg) -> suggestions.put(cat, avg.multiply(BigDecimal.valueOf(1.1))));
        return suggestions;
    }

    private String formatMonth(LocalDate date) {
        return "%04d-%02d".formatted(date.getYear(), date.getMonthValue());
    }

    public static class BudgetStatus {
        public String month;
        public BigDecimal totalBudget;
        public BigDecimal totalSpent;
        public BigDecimal totalPercentSpent;
        public Boolean isOverBudget;
        public Map<String, BudgetCategoryStatus> categoryStatus;
    }

    public static class BudgetCategoryStatus {
        public Expense.ExpenseCategory category;
        public BigDecimal budgetLimit;
        public BigDecimal spent;
        public BigDecimal remaining;
        public BigDecimal percentSpent;
        public Budget.BudgetStatus status;
    }

    public static class BudgetAlert {
        public Expense.ExpenseCategory category;
        public BigDecimal budgetLimit;
        public BigDecimal spent;
        public BigDecimal remaining;
        public BigDecimal percentSpent;
        public Budget.BudgetStatus status;
        public String message;
    }

    public static class SpendingInsights {
        public Map<String, BigDecimal> averageSpendingByCategory;
        public Map<String, List<BigDecimal>> trends;
        public List<Map.Entry<String, BigDecimal>> highestSpendingCategories;
        public List<SavingsOpportunity> potentialSavings;
    }

    public static class SavingsOpportunity {
        public String category;
        public BigDecimal currentSpending;
        public BigDecimal potentialSavings;
        public String suggestion;
    }
}
