package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Unified transaction ledger - normalizes transactions from all sources:
 * Groww, bank PDFs, credit card PDFs, MF statements, manual uploads.
 */
@Entity
@Table(name = "cfo_transactions", indexes = {
    @Index(name = "idx_txn_user_date", columnList = "user_id, transaction_date DESC"),
    @Index(name = "idx_txn_type", columnList = "transaction_type"),
    @Index(name = "idx_txn_source", columnList = "source"),
    @Index(name = "idx_txn_hash", columnList = "dedup_hash", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private LocalDate transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionSource source;

    /** Amount in INR */
    @Column(nullable = false)
    private BigDecimal amount;

    /** Stock/MF symbol if applicable */
    private String symbol;

    /** Name of stock, fund, merchant, etc. */
    private String name;

    /** Number of units (for stocks/MF) */
    private BigDecimal quantity;

    /** Price per unit (for stocks/MF) */
    private BigDecimal pricePerUnit;

    /** Description / narration from bank */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Bank reference / UTR / order ID */
    private String referenceNumber;

    /** SHA-256 hash for deduplication */
    @Column(nullable = false)
    private String dedupHash;

    /** Linked PDF upload ID if parsed from a document */
    private Long pdfUploadId;

    /** Whether this was synced to the main Expense table */
    @Builder.Default
    private Boolean syncedToExpense = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum TransactionType {
        BUY,
        SELL,
        DIVIDEND,
        INTEREST,
        EXPENSE,
        INCOME,
        TRANSFER,
        FEE,
        TAX,
        OTHER
    }

    public enum TransactionSource {
        GROWW,
        BANK_PDF,
        CREDIT_CARD_PDF,
        MF_PDF,
        DEMAT_PDF,
        MANUAL
    }
}
