package org.amit.finwise.cfo.model.macro;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "policy_quant_signal_queue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyQuantSignalQueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sourceEventCardId;
    private String parameterKey;       // e.g. "riskFreeRate" or "NBFC_shock"
    private double proposedValue;
    private double currentValue;
    private double confidence;

    @Enumerated(EnumType.STRING)
    private SignalStatus status;

    private Double overrideValue;
    private String rejectReason;
    private String resolvedBy;
    private Instant resolvedAt;

    @CreationTimestamp
    private Instant createdAt;

    public enum SignalStatus {
        PENDING, AUTO_APPROVE, CONFIRMED, REJECTED, OVERRIDDEN
    }
}
