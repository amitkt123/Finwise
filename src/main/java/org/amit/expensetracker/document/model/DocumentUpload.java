package org.amit.expensetracker.document.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "document_uploads", indexes = {
    @Index(name = "idx_doc_user",   columnList = "user_id"),
    @Index(name = "idx_doc_status", columnList = "parse_status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false)
    private String originalFilename;

    @Column(nullable = false)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    private DocumentType documentType;

    private String institution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ParseStatus parseStatus = ParseStatus.PENDING;

    @Builder.Default
    private Integer expensesExtracted = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private String statementPeriodStart;
    private String statementPeriodEnd;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public enum DocumentType {
        BANK_STATEMENT,
        CREDIT_CARD_STATEMENT,
        MF_STATEMENT,
        DEMAT_STATEMENT,
        UPI_STATEMENT,
        TRANSACTION_RECEIPT,
        UNKNOWN
    }

    public enum ParseStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        PASSWORD_REQUIRED
    }
}
