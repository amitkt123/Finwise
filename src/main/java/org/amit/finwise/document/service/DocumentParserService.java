package org.amit.finwise.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.model.ParsedDocument;
import org.amit.finwise.document.model.ParsedTransaction;
import org.amit.finwise.document.repository.DocumentUploadRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParserService {

    private static final DateTimeFormatter EN_MMM_D_YYYY =
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EN_DD_MMM_YYYY =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter EN_DD_MMMM_YYYY =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final DocumentUploadRepository uploadRepository;
    private final PdfTextExtractionService pdfTextExtractionService;

    @Value("${document.upload-dir:/tmp/finwise-docs}")
    private String uploadDir;

    @Transactional
    public DocumentUpload uploadAndParse(String userId, MultipartFile file, String password) {

        Path stored = storeFile(file);
        DocumentUpload upload = DocumentUpload.builder()
                .userId(userId)
                .originalFilename(file.getOriginalFilename())
                .storagePath(stored.toString())
                .parseStatus(DocumentUpload.ParseStatus.PROCESSING)
                .build();
        upload = uploadRepository.save(upload);

        try {
            ParsedDocument parsed = parseFile(stored.toFile(), password, upload.getId(), upload.getOriginalFilename());
            upload.setDocumentType(parsed.getDocumentType());
            upload.setInstitution(parsed.getInstitution());
            upload.setExtractedText(parsed.getExtractedText());
            upload.setParsedRecordsExtracted(parsed.getTransactionCount());
            upload.setParseStatus(parsed.getParseStatus());
            upload.setErrorMessage(parsed.getErrorMessage());

            log.info("Parsed {} records from {} ({})",
                    parsed.getTransactionCount(), file.getOriginalFilename(), parsed.getInstitution());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Document parsing failed for '{}': {} - root cause: {}",
                    file.getOriginalFilename(), e.getMessage(), cause.toString());
            upload.setParseStatus(DocumentUpload.ParseStatus.FAILED);
            upload.setErrorMessage(e.getMessage() + (cause != e ? " - " + cause.getMessage() : ""));
        }

        return uploadRepository.save(upload);
    }

    public ParsedDocument parseFile(File file, String password) {
        return parseFile(file, password, null, file.getName());
    }

    public ParsedDocument parseBytes(byte[] bytes, String password, String originalFilename) {
        String text = pdfTextExtractionService.extract(bytes, password);
        return parseText(text, null, originalFilename);
    }

    public ParsedDocument parseStream(InputStream inputStream, String password, String originalFilename) {
        String text = pdfTextExtractionService.extract(inputStream, password);
        return parseText(text, null, originalFilename);
    }

    public ParsedDocument parseText(String text, Long documentId, String originalFilename) {
        if (text == null) {
            return ParsedDocument.builder()
                    .documentId(documentId)
                    .originalFilename(originalFilename)
                    .documentType(DocumentUpload.DocumentType.UNKNOWN)
                    .institution("UNKNOWN")
                    .parseStatus(DocumentUpload.ParseStatus.PASSWORD_REQUIRED)
                    .transactions(List.of())
                    .build();
        }

        DetectedType detected = detectType(text);
        List<ParsedTransaction> transactions = parseTransactions(text, detected);
        return ParsedDocument.builder()
                .documentId(documentId)
                .originalFilename(originalFilename)
                .documentType(detected.documentType())
                .institution(detected.institution())
                .parseStatus(DocumentUpload.ParseStatus.COMPLETED)
                .extractedText(text)
                .transactions(transactions)
                .build();
    }

    public Optional<ParsedDocument> getParsedDocument(Long id) {
        return uploadRepository.findById(id)
                .map(upload -> parseText(upload.getExtractedText(), upload.getId(), upload.getOriginalFilename()));
    }

    public List<DocumentUpload> getDocuments(String userId) {
        return uploadRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public Optional<DocumentUpload> getDocument(Long id) {
        return uploadRepository.findById(id);
    }

    private ParsedDocument parseFile(File file, String password, Long documentId, String originalFilename) {
        String text = pdfTextExtractionService.extract(file, password);
        return parseText(text, documentId, originalFilename);
    }

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

    private List<ParsedTransaction> parseTransactions(String text, DetectedType type) {
        return switch (type.institution()) {
            case "HDFC" -> parseHdfc(text);
            case "ICICI" -> parseIcici(text);
            case "SBI" -> parseSbi(text);
            case "CAMS" -> parseMf(text);
            case "PHONEPE" -> parsePhonePe(text);
            case "PAYTM" -> parsePaytm(text);
            case "GPAY" -> parseGPay(text);
            default -> parseGeneric(text);
        };
    }

    private List<ParsedTransaction> parseHdfc(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}/\\d{2}/\\d{2,4})\\s+(.+?)\\s+(\\d+\\.\\d{2})?\\s+(\\d+\\.\\d{2})?\\s+(\\d+\\.\\d{2})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = parseFlexDate(m.group(1));
                String narration = m.group(2).trim();
                String withdrawalStr = m.group(3);
                if (withdrawalStr == null || withdrawalStr.isBlank()) continue;
                BigDecimal amount = new BigDecimal(withdrawalStr.replace(",", ""));
                transactions.add(buildTransaction(date, amount, narration, null,
                        ParsedTransaction.TransactionDirection.DEBIT, "BANK_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping HDFC row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parseIcici(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}-\\d{2}-\\d{4})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})\\s+(Dr|Cr)",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                if (!"Dr".equalsIgnoreCase(m.group(4))) continue;
                LocalDate date = LocalDate.parse(m.group(1), DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH));
                String particulars = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                transactions.add(buildTransaction(date, amount, particulars, null,
                        ParsedTransaction.TransactionDirection.DEBIT, "BANK_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping ICICI row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parseSbi(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2} \\w{3} \\d{4})\\s+(.+?)\\s+([\\d,]+\\.\\d{2})?\\s+([\\d,]+\\.\\d{2})?\\s+[\\d,]+\\.\\d{2}",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                String debitStr = m.group(3);
                if (debitStr == null || debitStr.isBlank()) continue;
                LocalDate date = LocalDate.parse(m.group(1), EN_DD_MMM_YYYY);
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(debitStr.replace(",", ""));
                transactions.add(buildTransaction(date, amount, desc, null,
                        ParsedTransaction.TransactionDirection.DEBIT, "BANK_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping SBI row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parseMf(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{2}-\\w{3}-\\d{4})\\s+(Purchase|Redemption|Switch|Dividend)\\s+([\\d,]+\\.\\d{3,4})\\s+([\\d,]+\\.\\d{2,4})\\s+([\\d,]+\\.\\d{2,4})",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                String txnType = m.group(2);
                if (!txnType.equalsIgnoreCase("Purchase")) continue;
                LocalDate date = LocalDate.parse(m.group(1), EN_DD_MMMM_YYYY);
                BigDecimal amount = new BigDecimal(m.group(5).replace(",", ""));
                transactions.add(buildTransaction(date, amount, "MF Purchase - " + txnType, null,
                        ParsedTransaction.TransactionDirection.PURCHASE, "MF_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping MF row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parsePhonePe(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();

        Pattern datePat = Pattern.compile("^(\\w{3}\\s+\\d{1,2},\\s+\\d{4})$");
        Pattern amountPat = Pattern.compile("^(Debit|Credit)\\s+INR\\s+([\\d,]+\\.\\d{2})$",
                Pattern.CASE_INSENSITIVE);

        LocalDate currentDate = null;
        String currentMerchant = null;

        for (String raw : text.split("\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            Matcher dm = datePat.matcher(line);
            if (dm.matches()) {
                try {
                    currentDate = LocalDate.parse(dm.group(1), EN_MMM_D_YYYY);
                    currentMerchant = null;
                } catch (Exception _) {
                    log.debug("PhonePe: unrecognised date '{}'", line);
                }
                continue;
            }

            if (line.startsWith("Paid to ") || line.startsWith("Payment to ")
                    || line.startsWith("Sent to ") || line.startsWith("Transfer to ")
                    || line.startsWith("Transferred to ")) {
                currentMerchant = line.replaceFirst(
                        "(?i)^(Paid to |Payment to |Sent to |Transfer to |Transferred to )", "").trim();
                continue;
            }
            if (line.startsWith("Received from ") || line.startsWith("Refund from ")
                    || line.startsWith("Cashback from ") || line.startsWith("Reward from ")) {
                currentMerchant = null;
                continue;
            }

            Matcher am = amountPat.matcher(line);
            if (am.matches() && currentDate != null) {
                boolean lineIsDebit = "Debit".equalsIgnoreCase(am.group(1));
                if (lineIsDebit) {
                    String merchant = currentMerchant != null ? currentMerchant : "Unknown";
                    String desc = currentMerchant != null ? "Paid to " + currentMerchant : "PhonePe Debit";
                    try {
                        BigDecimal amount = new BigDecimal(am.group(2).replace(",", ""));
                        transactions.add(buildTransaction(currentDate, amount, desc, merchant,
                                ParsedTransaction.TransactionDirection.DEBIT, "UPI_STATEMENT"));
                    } catch (Exception _) {
                        log.debug("PhonePe: could not parse amount '{}'", line);
                    }
                }
                currentMerchant = null;
            }
        }

        return transactions;
    }

    private List<ParsedTransaction> parsePaytm(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(.{3,80})\\s+" +
                "Debit\\s+" +
                "[\\u20B9]([\\d,]+(?:\\.\\d{2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = LocalDate.parse(m.group(1).trim(),
                        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                transactions.add(buildTransaction(date, amount, desc, extractMerchant(desc),
                        ParsedTransaction.TransactionDirection.DEBIT, "UPI_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping Paytm row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parseGPay(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(?:Paid to|Sent to|Debited)\\s+(.{2,60})\\s+" +
                "[\\u20B9]([\\d,]+(?:\\.\\d{2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = LocalDate.parse(m.group(1).trim(),
                        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
                String merchant = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                transactions.add(buildTransaction(date, amount, "GPay - " + merchant, merchant,
                        ParsedTransaction.TransactionDirection.DEBIT, "UPI_STATEMENT"));
            } catch (Exception e) {
                log.debug("Skipping GPay row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private List<ParsedTransaction> parseGeneric(String text) {
        List<ParsedTransaction> transactions = new ArrayList<>();
        Pattern p = Pattern.compile(
                "(\\d{1,2}[/ -]\\d{1,2}[/ -]\\d{2,4}|\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+" +
                "(.{5,80})\\s+" +
                "(?:Rs\\.?|INR|[\\u20B9])?\\s*([\\d,]+\\.\\d{2})",
                Pattern.MULTILINE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            try {
                LocalDate date = parseFlexDate(m.group(1));
                String desc = m.group(2).trim();
                BigDecimal amount = new BigDecimal(m.group(3).replace(",", ""));
                transactions.add(buildTransaction(date, amount, desc, null,
                        ParsedTransaction.TransactionDirection.UNKNOWN, "GENERIC"));
            } catch (Exception e) {
                log.debug("Skipping generic row: {}", e.getMessage());
            }
        }
        return transactions;
    }

    private String extractMerchant(String desc) {
        if (desc == null) return null;
        String lower = desc.toLowerCase();
        for (String prefix : List.of("paid to ", "payment to ", "sent to ", "transferred to ")) {
            if (lower.startsWith(prefix)) {
                String rest = desc.substring(prefix.length()).trim();
                return rest.split("[\\d/\\-]")[0].trim();
            }
        }
        if (lower.startsWith("upi/")) {
            String[] parts = desc.split("/");
            return parts.length > 1 ? parts[1].trim() : desc;
        }
        return null;
    }

    private ParsedTransaction buildTransaction(LocalDate date, BigDecimal amount,
                                               String description, String merchant,
                                               ParsedTransaction.TransactionDirection direction,
                                               String sourceHint) {
        return ParsedTransaction.builder()
                .transactionDate(date)
                .amount(amount)
                .direction(direction)
                .description(description.length() > 200 ? description.substring(0, 200) : description)
                .merchant(merchant)
                .sourceHint(sourceHint)
                .build();
    }

    private LocalDate parseFlexDate(String raw) {
        raw = raw.replace("-", "/");
        String[] parts = raw.split("/");
        if (parts.length == 3) {
            int year = parts[2].length() == 2 ? 2000 + Integer.parseInt(parts[2]) : Integer.parseInt(parts[2]);
            return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        }
        return LocalDate.parse(raw, DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
    }

    private Path storeFile(MultipartFile file) {
        try {
            Path dir = Path.of(uploadDir);
            Files.createDirectories(dir);
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.pdf";
            String filename = UUID.randomUUID() + "_" + originalName;
            Path target = dir.resolve(filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
            return target;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store uploaded document", e);
        }
    }
}
