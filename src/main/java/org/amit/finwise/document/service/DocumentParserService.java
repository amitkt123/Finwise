package org.amit.finwise.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.budget.service.BudgetMonitorService;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.repository.DocumentUploadRepository;
import org.amit.finwise.expense.model.Expense;
import org.amit.finwise.expense.repository.ExpenseRepository;
import org.amit.finwise.expense.service.ExpenseService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserService {

    // Always parse English month abbreviations (Jan/Feb/Mar…) regardless of JVM locale
    private static final DateTimeFormatter EN_MMM_D_YYYY =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EN_DD_MMM_YYYY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EN_DD_MMMM_YYYY =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final DocumentUploadRepository uploadRepository;
    private final ExpenseRepository expenseRepository;
    private final ExpenseService expenseService;
    private final BudgetMonitorService budgetMonitorService;
    private final PdfTextExtractionService pdfTextExtractionService;

    @Value("${document.upload-dir:/tmp/finwise-docs}")
    private String uploadDir;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    // ── Upload Entry Point ────────────────────────────────────────────────────

    @Transactional
    public DocumentUpload uploadAndParse(MultipartFile file, String password) {
        String userId = defaultUserId;

        Path stored = storeFile(file);
        DocumentUpload upload = DocumentUpload.builder()
                .userId(userId)
                .originalFilename(file.getOriginalFilename())
                .storagePath(stored.toString())
                .parseStatus(DocumentUpload.ParseStatus.PROCESSING)
                .build();
        upload = uploadRepository.save(upload);

        try {
            String text = extractText(stored.toFile(), password);
            if (text == null) {
                upload.setParseStatus(DocumentUpload.ParseStatus.PASSWORD_REQUIRED);
                return uploadRepository.save(upload);
            }

            DetectedType detected = detectType(text);
            upload.setDocumentType(detected.documentType());
            upload.setInstitution(detected.institution());

            final Long uploadId = upload.getId();
            List<Expense> expenses = parseExpenses(text, detected, userId);
            int saved = 0;
            for (Expense expense : expenses) {
                expense.setPdfUploadId(uploadId);
                expenseRepository.save(expense);
                budgetMonitorService.updateBudgetForExpense(userId, expense);
                saved++;
            }

            upload.setExpensesExtracted(saved);
            upload.setParseStatus(DocumentUpload.ParseStatus.COMPLETED);
            log.info("Parsed {} expenses from {} ({})", saved, file.getOriginalFilename(), detected.institution());

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Document parsing failed for '{}': {} — root cause: {}",
                    file.getOriginalFilename(), e.getMessage(), cause.toString());
            upload.setParseStatus(DocumentUpload.ParseStatus.FAILED);
            upload.setErrorMessage(e.getMessage() + (cause != e ? " — " + cause.getMessage() : ""));
        }

        return uploadRepository.save(upload);
    }

    // ── Query Methods (used by DocumentController) ────────────────────────────

    public List<DocumentUpload> getDocuments() {
        return uploadRepository.findByUserIdOrderByCreatedAtDesc(defaultUserId);
    }

    public Optional<DocumentUpload> getDocument(Long id) {
        return uploadRepository.findById(id);
    }

    // ── Text Extraction ───────────────────────────────────────────────────────

    private String extractText(File file, String password) {
        return pdfTextExtractionService.extract(file, password);
    }

    // ── Institution Detection ─────────────────────────────────────────────────

    private DetectedType detectType(String text) {
        String lower = text.toLowerCase();

        if (lower.contains("hdfc bank") || lower.contains("hdfc ltd"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "HDFC");
        if (lower.contains("icici bank"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "ICICI");
        if (lower.contains("state bank of india") || lower.contains("sbi"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "SBI");
        if (lower.contains("axis bank"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "AXIS");
        if (lower.contains("kotak mahindra bank"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "KOTAK");
        if (lower.contains("yes bank"))
            return new DetectedType(DocumentUpload.DocumentType.BANK_STATEMENT, "YES_BANK");
        if (lower.contains("cams") || lower.contains("karvy") || lower.contains("kfintech"))
            return new DetectedType(DocumentUpload.DocumentType.MF_STATEMENT, "CAMS");
        if (lower.contains("nsdl") || lower.contains("cdsl") || lower.contains("demat"))
            return new DetectedType(DocumentUpload.DocumentType.DEMAT_STATEMENT, "NSDL");
        if (lower.contains("credit card") && lower.contains("statement"))
            return new DetectedType(DocumentUpload.DocumentType.CREDIT_CARD_STATEMENT, "UNKNOWN");
        if (lower.contains("phonepe") || lower.contains("phone pe"))
            return new DetectedType(DocumentUpload.DocumentType.UPI_STATEMENT, "PHONEPE");
        if (lower.contains("paytm"))
            return new DetectedType(DocumentUpload.DocumentType.UPI_STATEMENT, "PAYTM");
        if (lower.contains("google pay") || lower.contains("gpay"))
            return new DetectedType(DocumentUpload.DocumentType.UPI_STATEMENT, "GPAY");

        return new DetectedType(DocumentUpload.DocumentType.UNKNOWN, "UNKNOWN");
    }

    record DetectedType(DocumentUpload.DocumentType documentType, String institution) {}

    // ── Parsing Dispatch ──────────────────────────────────────────────────────

    private List<Expense> parseExpenses(String text, DetectedType type, String userId) {
        return switch (type.institution()) {
            case "HDFC"     -> parseHdfc(text, userId);
            case "ICICI"    -> parseIcici(text, userId);
            case "SBI"      -> parseSbi(text, userId);
            case "CAMS"     -> parseMf(text, userId);
            case "PHONEPE"  -> parsePhonePe(text, userId);
            case "PAYTM"    -> parsePaytm(text, userId);
            case "GPAY"     -> parseGPay(text, userId);
            default         -> parseGeneric(text, userId);
        };
    }

    // ── Bank Statement Parsers ────────────────────────────────────────────────

    /** HDFC: Date | Narration | Chq/Ref | Value Date | Withdrawal | Deposit | Balance */
    private List<Expense> parseHdfc(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{2,4})\\s+(.+?)\\s+(\\d+\\.\\d{2})?\\s+(\\d+\\.\\d{2})?\\s+(\\d+\\.\\d{2})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = parseFlexDate(m.group(1));
                String narration = m.group(2).trim();
                String withdrawalStr = m.group(3);
                if (withdrawalStr == null || withdrawalStr.isBlank()) continue; // skip credits

                BigDecimal amount = new BigDecimal(withdrawalStr.replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, narration, null, Expense.ExpenseSource.BANK_PDF));
            } catch (Exception e) {
                log.debug("Skipping HDFC row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    /** ICICI: Date | Particulars | Amount | Dr/Cr | Balance */
    private List<Expense> parseIcici(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}-\\d{2}-\\d{4})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})\\s+(Dr|Cr)",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                if (!"Dr".equalsIgnoreCase(m.group(4))) continue; // skip credits
                LocalDate date = LocalDate.parse(m.group(1), DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH));
                String particulars = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, particulars, null, Expense.ExpenseSource.BANK_PDF));
            } catch (Exception e) {
                log.debug("Skipping ICICI row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    /** SBI: Txn Date | Value Date | Description | Ref No | Debit | Credit | Balance */
    private List<Expense> parseSbi(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2} \\w{3} \\d{4})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})?\\s+([\\d,]+\\.\\d{2})?\\s+[\\d,]+\\.\\d{2}",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                String debitStr = m.group(3);
                if (debitStr == null || debitStr.isBlank()) continue; // skip credits
                LocalDate date = LocalDate.parse(m.group(1), EN_DD_MMM_YYYY);
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(debitStr.replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, desc, null, Expense.ExpenseSource.BANK_PDF));
            } catch (Exception e) {
                log.debug("Skipping SBI row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    /** CAMS / MF Statement: Purchase entries → INVESTMENT category */
    private List<Expense> parseMf(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}-\\w{3}-\\d{4})\\s+(Purchase|Redemption|Switch|Dividend)\\s+([\\d,]+\\.\\d{3,4})\\s+([\\d,]+\\.\\d{2,4})\\s+([\\d,]+\\.\\d{2,4})",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                String txnType = m.group(2);
                if (!txnType.equalsIgnoreCase("Purchase")) continue; // only purchases are expenses
                LocalDate date = LocalDate.parse(m.group(1), EN_DD_MMMM_YYYY);
                BigDecimal amount = new BigDecimal(m.group(5).replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, "MF Purchase - " + txnType,
                        null, Expense.ExpenseSource.MF_PDF));
            } catch (Exception e) {
                log.debug("Skipping MF row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    // ── UPI Statement Parsers ─────────────────────────────────────────────────

    /**
     * PhonePe transaction statement — multi-line format:
     * <p>
     *   Apr 04, 2025          ← date line
     *   07:58 PM              ← time line (ignored)
     *   Paid to VINIL KUMAR   ← merchant line
     *   Transaction ID : T... ← skipped
     *   UTR No : ...          ← skipped
     *   Debited from XX3831   ← skipped
     *   Debit INR 70.00       ← type + amount line
     */
    private List<Expense> parsePhonePe(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();

        Pattern datePat   = Pattern.compile("^(\\w{3}\\s+\\d{1,2},\\s+\\d{4})$");
        Pattern amountPat = Pattern.compile("^(Debit|Credit)\\s+INR\\s+([\\d,]+\\.\\d{2})$",
                Pattern.CASE_INSENSITIVE);

        LocalDate currentDate     = null;
        String    currentMerchant = null;
        boolean   isDebit         = false;

        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            // Date line: "Apr 04, 2025"
            Matcher dm = datePat.matcher(line);
            if (dm.matches()) {
                try {
                    currentDate     = LocalDate.parse(dm.group(1),
                            EN_MMM_D_YYYY);
                    currentMerchant = null;
                    isDebit         = false;
                } catch (Exception e) {
                    log.debug("PhonePe: unrecognised date '{}'", line);
                }
                continue;
            }

            // Merchant line: "Paid to X" / "Sent to X" / "Payment to X" — debits
            if (line.startsWith("Paid to ") || line.startsWith("Payment to ")
                    || line.startsWith("Sent to ") || line.startsWith("Transfer to ")
                    || line.startsWith("Transferred to ")) {
                currentMerchant = line.replaceFirst(
                        "(?i)^(Paid to |Payment to |Sent to |Transfer to |Transferred to )", "").trim();
                isDebit = true;
                continue;
            }
            if (line.startsWith("Received from ") || line.startsWith("Refund from ")
                    || line.startsWith("Cashback from ") || line.startsWith("Reward from ")) {
                currentMerchant = null;
                isDebit = false;
                continue;
            }

            // Amount line: "Debit INR 70.00"
            Matcher am = amountPat.matcher(line);
            if (am.matches() && currentDate != null) {
                boolean lineIsDebit = "Debit".equalsIgnoreCase(am.group(1));
                if (lineIsDebit) {
                    // capture all debits — use fallback label if merchant prefix wasn't recognised
                    String merchant = (currentMerchant != null) ? currentMerchant : "Unknown";
                    String desc     = (currentMerchant != null) ? "Paid to " + currentMerchant : "PhonePe Debit";
                    try {
                        BigDecimal amount = new BigDecimal(am.group(2).replace(",", ""));
                        expenses.add(buildExpense(userId, currentDate, amount,
                                desc, merchant, Expense.ExpenseSource.UPI_PDF));
                    } catch (Exception e) {
                        log.debug("PhonePe: could not parse amount '{}'", line);
                    }
                }
                // Reset for next transaction (date line will also reset, but amount comes last)
                currentMerchant = null;
                isDebit = false;
            }
        }

        return expenses;
    }

    /**
     * Paytm statement – similar tabular format, uses "Debit" keyword.
     * Row: DD MMM YYYY  |  Description  |  Debit  |  ₹amount
     */
    private List<Expense> parsePaytm(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(.{3,80})\\s+" +
                "Debit\\s+" +
                "[₹\\u20B9]([\\d,]+(?:\\.\\d{2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = LocalDate.parse(m.group(1).trim(),
                        DateTimeFormatter.ofPattern("d MMM yyyy"));
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, desc,
                        extractMerchant(desc), Expense.ExpenseSource.UPI_PDF));
            } catch (Exception e) {
                log.debug("Skipping Paytm row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    /**
     * Google Pay statement – uses "Debited" and merchant name inline.
     */
    private List<Expense> parseGPay(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(?:Paid to|Sent to|Debited)\\s+(.{2,60})\\s+" +
                "[₹\\u20B9]([\\d,]+(?:\\.\\d{2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = LocalDate.parse(m.group(1).trim(),
                        DateTimeFormatter.ofPattern("d MMM yyyy"));
                String merchant = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                expenses.add(buildExpense(userId, date, amount,
                        "GPay - " + merchant, merchant, Expense.ExpenseSource.UPI_PDF));
            } catch (Exception e) {
                log.debug("Skipping GPay row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    /** Extract a short merchant name from a longer UPI description. */
    private String extractMerchant(String desc) {
        // "Paid to Swiggy Order 1234" → "Swiggy"
        // "UPI/SWIGGY/12345" → "SWIGGY"
        if (desc == null) return null;
        String lower = desc.toLowerCase();
        for (String prefix : List.of("paid to ", "payment to ", "sent to ", "transferred to ")) {
            if (lower.startsWith(prefix)) {
                String rest = desc.substring(prefix.length()).trim();
                // Take only up to first number or special char
                return rest.split("[\\d/\\-]")[0].trim();
            }
        }
        // UPI ref format: "UPI/MERCHANT/ref"
        if (lower.startsWith("upi/")) {
            String[] parts = desc.split("/");
            return parts.length > 1 ? parts[1].trim() : desc;
        }
        return null;
    }

    /** Generic fallback: date + amount pattern (handles Rs., INR, ₹) */
    private List<Expense> parseGeneric(String text, String userId) {
        List<Expense> expenses = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}[/ -]\\d{1,2}[/ -]\\d{2,4}|\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(.{5,80})\\s+" +
                "(?:Rs\\.?|INR|[₹\u20B9])?\\s*([\\d,]+\\.\\d{2})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = parseFlexDate(m.group(1));
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                expenses.add(buildExpense(userId, date, amount, desc, null, Expense.ExpenseSource.BANK_PDF));
            } catch (Exception e) {
                log.debug("Skipping generic row: {}", e.getMessage());
            }
        }
        return expenses;
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private Expense buildExpense(String userId, LocalDate date, BigDecimal amount,
                                  String description, String merchant, Expense.ExpenseSource source) {
        Expense.ExpenseCategory category = expenseService.detectCategory(description, merchant);
        return Expense.builder()
                .userId(userId)
                .amount(amount)
                .category(category)
                .description(description.length() > 200 ? description.substring(0, 200) : description)
                .expenseDate(date)
                .merchant(merchant)
                .source(source)
                .budgetCategory(category.toString())
                .build();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private LocalDate parseFlexDate(String raw) {
        raw = raw.replace("-", "/");
        String[] parts = raw.split("/");
        int year = parts[2].length() == 2 ? 2000 + Integer.parseInt(parts[2]) : Integer.parseInt(parts[2]);
        return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
    }

    private Path storeFile(MultipartFile file) {
        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
            String filename = UUID.randomUUID() + "_" + originalName;
            Path target = dir.resolve(filename);
            // Use stream copy — transferTo(Path) can silently fail if the servlet
            // temp file has already been cleaned up before this method is reached.
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded document", e);
        }
    }
}
