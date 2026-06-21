package org.amit.finwise.budget.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.budget.model.Budget;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.expense.model.Expense;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
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
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String month,
            @RequestParam Expense.ExpenseCategory category,
            @RequestParam BigDecimal limit) {
        return ResponseEntity.ok(
                budgetMonitorService.setBudget(principal.getUsername(), month, category, limit));
    }

    @GetMapping("/budget/current")
    public ResponseEntity<BudgetMonitorService.BudgetStatus> getCurrentBudgetStatus(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
                budgetMonitorService.getCurrentMonthBudgetStatus(principal.getUsername()));
    }

    @GetMapping("/budget/alerts")
    public ResponseEntity<List<BudgetMonitorService.BudgetAlert>> getBudgetAlerts(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String month) {
        return ResponseEntity.ok(
                budgetMonitorService.getBudgetAlerts(principal.getUsername(), month));
    }

    @GetMapping("/insights")
    public ResponseEntity<BudgetMonitorService.SpendingInsights> getSpendingInsights(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(
                budgetMonitorService.getSpendingInsights(principal.getUsername(), months));
    }

    @GetMapping("/budget/suggest")
    public ResponseEntity<Map<String, BigDecimal>> suggestBudget(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "3") int months) {
        return ResponseEntity.ok(
                budgetMonitorService.suggestBudget(principal.getUsername(), months));
    }
}
