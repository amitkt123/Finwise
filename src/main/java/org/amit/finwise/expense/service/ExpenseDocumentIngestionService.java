package org.amit.finwise.expense.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.document.model.ParsedDocument;
import org.amit.finwise.document.model.ParsedTransaction;
import org.amit.finwise.document.service.DocumentParserService;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseDocumentIngestionService {

    private final DocumentParserService documentParserService;
    private final ExpenseRepository expenseRepository;
    private final ExpenseService expenseService;
    private final BudgetMonitorService budgetMonitorService;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    @Transactional
    public ImportResult importDocument(Long documentId) {
        ParsedDocument parsedDocument = documentParserService.getParsedDocument(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        List<Expense> saved = new ArrayList<>();
        for (ParsedTransaction transaction : parsedDocument.getTransactions()) {
            if (!isExpenseLike(transaction)) {
                continue;
            }

            Expense expense = toExpense(defaultUserId, documentId, transaction);
            expense = expenseRepository.save(expense);
            budgetMonitorService.updateBudgetForExpense(defaultUserId, expense);
            saved.add(expense);
        }

        log.info("Imported {} expenses from parsed document {}", saved.size(), documentId);
        return new ImportResult(documentId, saved.size(), saved);
    }

    private boolean isExpenseLike(ParsedTransaction transaction) {
        return transaction.getDirection() == ParsedTransaction.TransactionDirection.DEBIT
                || transaction.getDirection() == ParsedTransaction.TransactionDirection.PURCHASE
                || transaction.getDirection() == ParsedTransaction.TransactionDirection.UNKNOWN;
    }

    private Expense toExpense(String userId, Long documentId, ParsedTransaction transaction) {
        Expense.ExpenseCategory category = expenseService.detectCategory(
                transaction.getDescription(), transaction.getMerchant());
        return Expense.builder()
                .userId(userId)
                .amount(transaction.getAmount())
                .category(category)
                .description(transaction.getDescription())
                .expenseDate(transaction.getTransactionDate())
                .merchant(transaction.getMerchant())
                .source(toExpenseSource(transaction.getSourceHint()))
                .budgetCategory(category.toString())
                .pdfUploadId(documentId)
                .build();
    }

    private Expense.ExpenseSource toExpenseSource(String sourceHint) {
        if ("MF_STATEMENT".equals(sourceHint)) {
            return Expense.ExpenseSource.MF_PDF;
        }
        if ("UPI_STATEMENT".equals(sourceHint)) {
            return Expense.ExpenseSource.UPI_PDF;
        }
        return Expense.ExpenseSource.BANK_PDF;
    }

    public record ImportResult(Long documentId, int expensesImported, List<Expense> expenses) {}
}
