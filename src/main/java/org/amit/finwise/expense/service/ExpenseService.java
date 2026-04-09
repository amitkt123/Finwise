package org.amit.finwise.expense.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenseRepository;

    @Transactional
    public Expense recordExpense(String userId, BigDecimal amount,
                                 Expense.ExpenseCategory category,
                                 String description, LocalDate expenseDate) {
        Expense expense = Expense.builder()
                .userId(userId)
                .amount(amount)
                .category(category)
                .description(description)
                .expenseDate(expenseDate)
                .source(Expense.ExpenseSource.MANUAL)
                .budgetCategory(category.toString())
                .build();
        log.info("Recording expense: {} - {} INR", category, amount);
        return expenseRepository.save(expense);
    }

    @Transactional
    public Expense parseAndRecordSMSExpense(String userId, String smsContent) {
        try {
            ExpenseData data = parseBankSMS(smsContent);
            Expense expense = Expense.builder()
                    .userId(userId)
                    .amount(data.amount)
                    .category(detectCategory(smsContent, data.merchant))
                    .description(smsContent.substring(0, Math.min(200, smsContent.length())))
                    .expenseDate(LocalDate.now())
                    .merchant(data.merchant)
                    .source(Expense.ExpenseSource.SMS)
                    .paymentMethod(data.paymentMethod)
                    .build();
            log.info("Parsed SMS expense: {} - {} INR from {}", expense.getCategory(), data.amount, data.merchant);
            return expenseRepository.save(expense);
        } catch (Exception e) {
            log.error("Error parsing SMS: {}", e.getMessage());
            throw new RuntimeException("Failed to parse expense SMS", e);
        }
    }

    private ExpenseData parseBankSMS(String sms) {
        ExpenseData data = new ExpenseData();
        Pattern amountPattern = Pattern.compile("(?:Rs\\.?|INR|Amount:?)\\s*(\\d+(?:\\.\\d{2})?)");
        Matcher amountMatcher = amountPattern.matcher(sms);
        if (amountMatcher.find()) {
            data.amount = new BigDecimal(amountMatcher.group(1));
        }
        Pattern merchantPattern = Pattern.compile("(?:at|@)\\s+([A-Za-z\\s]+?)(?:\\.|;|,|on|Date|Time|Ref)");
        Matcher merchantMatcher = merchantPattern.matcher(sms);
        if (merchantMatcher.find()) {
            data.merchant = merchantMatcher.group(1).trim();
        }
        if (sms.toLowerCase().contains("credit card") || sms.toLowerCase().contains("cc")) {
            data.paymentMethod = Expense.PaymentMethod.CREDIT_CARD;
        } else if (sms.toLowerCase().contains("debit card") || sms.toLowerCase().contains("dc")) {
            data.paymentMethod = Expense.PaymentMethod.DEBIT_CARD;
        } else if (sms.toLowerCase().contains("upi")) {
            data.paymentMethod = Expense.PaymentMethod.UPI;
        }
        return data;
    }

    public Expense.ExpenseCategory detectCategory(String sms, String merchant) {
        String text = (sms + " " + (merchant != null ? merchant : "")).toLowerCase();
        if (text.contains("restaurant") || text.contains("cafe") || text.contains("food") || text.contains("pizza")) return Expense.ExpenseCategory.FOOD_DINING;
        if (text.contains("grocery") || text.contains("supermarket") || text.contains("dmart") || text.contains("bigbasket")) return Expense.ExpenseCategory.GROCERIES;
        if (text.contains("uber") || text.contains("ola") || text.contains("taxi") || text.contains("petrol")) return Expense.ExpenseCategory.TRANSPORTATION;
        if (text.contains("electricity") || text.contains("water") || text.contains("internet") || text.contains("mobile")) return Expense.ExpenseCategory.UTILITIES;
        if (text.contains("movie") || text.contains("netflix") || text.contains("spotify")) return Expense.ExpenseCategory.ENTERTAINMENT;
        if (text.contains("amazon") || text.contains("flipkart") || text.contains("shopping")) return Expense.ExpenseCategory.SHOPPING;
        if (text.contains("hospital") || text.contains("pharmacy") || text.contains("medical")) return Expense.ExpenseCategory.HEALTHCARE;
        if (text.contains("insurance")) return Expense.ExpenseCategory.INSURANCE;
        if (text.contains("subscription") || text.contains("monthly")) return Expense.ExpenseCategory.SUBSCRIPTION;
        if (text.contains("mutual fund") || text.contains("stock") || text.contains("investment")) return Expense.ExpenseCategory.INVESTMENT;
        if (text.contains("transfer") || text.contains("savings")) return Expense.ExpenseCategory.SAVINGS;
        return Expense.ExpenseCategory.OTHER;
    }

    public MonthlySpendingSummary getMonthlySpendingSummary(String userId, int year, int month) {
        MonthlySpendingSummary summary = new MonthlySpendingSummary();
        summary.month = YearMonth.of(year, month);
        summary.totalSpent = expenseRepository.sumExpensesByMonth(userId, year, month);
        summary.averageExpense = expenseRepository.averageExpense(userId);
        summary.categoryBreakdown = new HashMap<>();
        for (Expense.ExpenseCategory category : Expense.ExpenseCategory.values()) {
            BigDecimal total = expenseRepository.sumExpensesByMonthAndCategory(userId, year, month, category);
            if (total.compareTo(BigDecimal.ZERO) > 0) {
                summary.categoryBreakdown.put(category, total);
            }
        }
        return summary;
    }

    public Page<Expense> getExpensesByDateRange(String userId, LocalDate startDate, LocalDate endDate, Pageable pageable) {
        return expenseRepository.findByUserInDateRange(userId, startDate, endDate, pageable);
    }

    public Map<String, BigDecimal> getSpendingTrend(String userId, int months) {
        Map<String, BigDecimal> trend = new LinkedHashMap<>();
        LocalDate now = LocalDate.now();
        for (int i = months - 1; i >= 0; i--) {
            LocalDate date = now.minusMonths(i);
            trend.put(date.toString(), expenseRepository.sumExpensesByMonth(userId, date.getYear(), date.getMonthValue()));
        }
        return trend;
    }

    private static class ExpenseData {
        BigDecimal amount;
        String merchant;
        Expense.PaymentMethod paymentMethod;
    }

    public static class MonthlySpendingSummary {
        public YearMonth month;
        public BigDecimal totalSpent;
        public BigDecimal averageExpense;
        public Map<Expense.ExpenseCategory, BigDecimal> categoryBreakdown;
    }
}
