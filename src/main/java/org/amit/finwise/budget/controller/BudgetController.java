package org.amit.finwise.budget.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.budget.model.Budget;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.expense.model.Expense;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetMonitorService budgetMonitorService;

    @PostMapping("/budget")
    public ResponseEntity<Budget> setBudget(
            @RequestParam String userId,
            @RequestParam String month,
            @RequestParam Expense.ExpenseCategory category,
            @RequestParam BigDecimal limit) {
        return ResponseEntity.ok(budgetMonitorService.setBudget(userId, month, category, limit));
    }

    @GetMapping("/budget/current")
    public ResponseEntity<BudgetMonitorService.BudgetStatus> getCurrentBudgetStatus(@RequestParam String userId) {
        return ResponseEntity.ok(budgetMonitorService.getCurrentMonthBudgetStatus(userId));
    }

    @GetMapping("/budget/alerts")
    public ResponseEntity<List<BudgetMonitorService.BudgetAlert>> getBudgetAlerts(
            @RequestParam String userId, @RequestParam String month) {
        return ResponseEntity.ok(budgetMonitorService.getBudgetAlerts(userId, month));
    }

    @GetMapping("/insights")
    public ResponseEntity<BudgetMonitorService.SpendingInsights> getSpendingInsights(
            @RequestParam String userId,
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(budgetMonitorService.getSpendingInsights(userId, months));
    }

    @GetMapping("/budget/suggest")
    public ResponseEntity<Map<String, BigDecimal>> suggestBudget(
            @RequestParam String userId,
            @RequestParam(defaultValue = "3") int months) {
        return ResponseEntity.ok(budgetMonitorService.suggestBudget(userId, months));
    }
}
