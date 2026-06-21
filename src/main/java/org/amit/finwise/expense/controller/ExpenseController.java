package org.amit.finwise.expense.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.expense.dto.ExpenseResponse;
import org.amit.finwise.expense.dto.RecordExpenseRequest;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.amit.finwise.expense.service.ExpenseDocumentIngestionService;
import org.amit.finwise.expense.service.ExpenseService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService expenseService;
    private final ExpenseDocumentIngestionService expenseDocumentIngestionService;
    private final BudgetMonitorService budgetMonitorService;
    private final ExpenseRepository expenseRepository;

    @PostMapping("/expense")
    public ResponseEntity<ExpenseResponse> recordExpense(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody RecordExpenseRequest request) {
        String userId = principal.getUsername();
        Expense expense = expenseService.recordExpense(
                userId, request.amount(), request.category(), request.description(), LocalDate.now());
        budgetMonitorService.updateBudgetForExpense(userId, expense);
        return ResponseEntity.ok(ExpenseResponse.from(expense));
    }

    @PostMapping("/expense/sms")
    public ResponseEntity<ExpenseResponse> parseSMSExpense(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody String smsContent) {
        String userId = principal.getUsername();
        Expense expense = expenseService.parseAndRecordSMSExpense(userId, smsContent);
        budgetMonitorService.updateBudgetForExpense(userId, expense);
        return ResponseEntity.ok(ExpenseResponse.from(expense));
    }

    @PostMapping("/expense/import/document/{documentId}")
    public ResponseEntity<ExpenseDocumentIngestionService.ImportResult> importDocumentExpenses(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long documentId) {
        return ResponseEntity.ok(
                expenseDocumentIngestionService.importDocument(principal.getUsername(), documentId));
    }

    @GetMapping("/expense/summary")
    public ResponseEntity<ExpenseService.MonthlySpendingSummary> getSpendingSummary(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam int year,
            @RequestParam int month) {
        return ResponseEntity.ok(
                expenseService.getMonthlySpendingSummary(principal.getUsername(), year, month));
    }

    @GetMapping("/expense/trend")
    public ResponseEntity<Map<String, BigDecimal>> getSpendingTrend(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(expenseService.getSpendingTrend(principal.getUsername(), months));
    }

    @GetMapping("/expense/range")
    public ResponseEntity<Page<ExpenseResponse>> getExpensesByRange(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseRepository
                .findByUserInDateRange(principal.getUsername(), startDate, endDate, PageRequest.of(page, size))
                .map(ExpenseResponse::from));
    }

    @GetMapping("/expenses")
    public ResponseEntity<Page<ExpenseResponse>> listExpenses(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                expenseRepository.findByUserId(principal.getUsername(), PageRequest.of(page, size))
                        .map(ExpenseResponse::from));
    }

    @GetMapping("/expenses/source/{source}")
    public ResponseEntity<Page<ExpenseResponse>> listExpensesBySource(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Expense.ExpenseSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseRepository
                .findByUserIdAndSource(principal.getUsername(), source, PageRequest.of(page, size))
                .map(ExpenseResponse::from));
    }
}
