package org.amit.finwise.policy.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "policy_falsification_records", indexes = {
    @Index(name = "idx_falsif_impact", columnList = "impact_id"),
    @Index(name = "idx_falsif_subject", columnList = "subject_key"),
    @Index(name = "idx_falsif_effective", columnList = "effective_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyFalsificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "impact_id", nullable = false)
    private PolicyImpact impact;

    @Column(name = "subject_key", length = 100)
    private String subjectKey;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    /** Trading-day horizon over which the reaction is measured. */
    @Column(name = "horizon_days")
    private Integer horizonDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "predicted_direction", length = 30)
    private PolicyImpactDirection predictedDirection;

    /** Sector/asset-class index return over the horizon from effectiveDate. */
    @Column(name = "realized_return")
    private Double realizedReturn;

    /** Excess return vs Nifty over the same horizon. */
    @Column(name = "excess_return_vs_nifty")
    private Double excessReturnVsNifty;

    /** True if the realized direction matches the predicted direction. */
    @Column(name = "direction_confirmed")
    private Boolean directionConfirmed;

    /** Shrinkage-adjusted confidence after this data point. */
    @Column(name = "updated_confidence")
    private Double updatedConfidence;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime checkedAt;
}
