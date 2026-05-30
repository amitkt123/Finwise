package org.amit.finwise.expense.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.amit.finwise.expense.service.ExpenseDocumentIngestionService;
import org.amit.finwise.expense.service.ExpenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
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

    @Value("${cfo.user.id}")
    private String defaultUserId;

    @PostMapping("/expense")
    public ResponseEntity<Expense> recordExpense(@RequestParam String userId,
                                                  @RequestParam BigDecimal amount,
                                                  @RequestParam Expense.ExpenseCategory category,
                                                  @RequestParam String description) {
        Expense expense = expenseService.recordExpense(userId, amount, category, description, LocalDate.now());
        budgetMonitorService.updateBudgetForExpense(userId, expense);
        return ResponseEntity.ok(expense);
    }

    @PostMapping("/expense/sms")
    public ResponseEntity<Expense> parseSMSExpense(@RequestParam String userId,
                                                    @RequestBody String smsContent) {
        Expense expense = expenseService.parseAndRecordSMSExpense(userId, smsContent);
        budgetMonitorService.updateBudgetForExpense(userId, expense);
        return ResponseEntity.ok(expense);
    }

    @PostMapping("/expense/import/document/{documentId}")
    public ResponseEntity<ExpenseDocumentIngestionService.ImportResult> importDocumentExpenses(
            @PathVariable Long documentId) {
        return ResponseEntity.ok(expenseDocumentIngestionService.importDocument(documentId));
    }

    @GetMapping("/expense/summary")
    public ResponseEntity<ExpenseService.MonthlySpendingSummary> getSpendingSummary(
            @RequestParam String userId, @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(expenseService.getMonthlySpendingSummary(userId, year, month));
    }

    @GetMapping("/expense/trend")
    public ResponseEntity<Map<String, BigDecimal>> getSpendingTrend(
            @RequestParam String userId,
            @RequestParam(defaultValue = "6") int months) {
        return ResponseEntity.ok(expenseService.getSpendingTrend(userId, months));
    }

    @GetMapping("/expense/range")
    public ResponseEntity<Page<Expense>> getExpensesByRange(
            @RequestParam String userId,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseRepository.findByUserInDateRange(
                userId, startDate, endDate, PageRequest.of(page, size)));
    }

    /**
     * GET /api/finance/expenses
     * Paginated list of all expenses for the configured user, newest first.
     */
    @GetMapping("/expenses")
    public ResponseEntity<Page<Expense>> listExpenses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseRepository.findByUserId(defaultUserId, PageRequest.of(page, size)));
    }

    /**
     * GET /api/finance/expenses/source/{source}
     * Filter expenses by source (e.g. UPI_PDF, BANK_PDF, SMS, MANUAL).
     */
    @GetMapping("/expenses/source/{source}")
    public ResponseEntity<Page<Expense>> listExpensesBySource(
            @PathVariable Expense.ExpenseSource source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(expenseRepository.findByUserIdAndSource(
                defaultUserId, source, PageRequest.of(page, size)));
    }
}
