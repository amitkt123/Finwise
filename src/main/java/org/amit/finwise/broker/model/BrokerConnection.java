package org.amit.finwise.broker.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "broker_connections",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "broker"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BrokerEnum broker;

    @Column(columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    /** Broker-issued account/client identifier some APIs require alongside the token (e.g. Angel One's client code). Not a secret. */
    private String brokerClientId;

    private Instant tokenExpiresAt;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.ACTIVE;

    private Instant lastSyncedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
