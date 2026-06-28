package org.amit.finwise.cfo.model.macro;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity @Table(name = "macro_state_audit")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class MacroStateAuditEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String fieldName;
    private double oldValue;
    private double newValue;
    private String source;
    private String confirmedBy;
    @CreationTimestamp
    private Instant createdAt;
}
