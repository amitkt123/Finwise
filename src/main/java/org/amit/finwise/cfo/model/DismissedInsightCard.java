package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "cfo_dismissed_insight_card",
       uniqueConstraints = @UniqueConstraint(name = "uq_dismissed_user_card", columnNames = {"user_id", "card_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DismissedInsightCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "card_id", nullable = false)
    private String cardId;

    @CreationTimestamp
    @Column(name = "dismissed_at", nullable = false, updatable = false)
    private Instant dismissedAt;
}
