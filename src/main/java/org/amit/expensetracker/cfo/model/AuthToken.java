package org.amit.expensetracker.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "cfo_auth_tokens")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    /** External service: GROWW | ZERODHA | BANK_HDFC | etc. */
    @Column(nullable = false)
    private String service;

    /** AES-encrypted JWT/Bearer token */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedToken;

    /** AES-encrypted credentials for auto-refresh (JSON: {username, password}) */
    @Column(columnDefinition = "TEXT")
    private String encryptedCredentials;

    /** Token expiry - null means unknown/no expiry */
    private LocalDateTime expiresAt;

    /** Last successful sync using this token */
    private LocalDateTime lastUsedAt;

    /** Whether auto-refresh is enabled */
    @Builder.Default
    private Boolean autoRefreshEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
