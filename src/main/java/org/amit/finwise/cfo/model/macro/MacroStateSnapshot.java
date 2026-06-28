package org.amit.finwise.cfo.model.macro;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity @Table(name = "macro_state_snapshot")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MacroStateSnapshot {
    @Id
    private String fieldName;          // e.g. "riskFreeRate"
    private double value;
    private String source;             // FBIL | ADMIN | AUTO
    private String lastConfirmedBy;    // FBIL | ADMIN | AUTO
    @UpdateTimestamp
    private Instant updatedAt;
}
