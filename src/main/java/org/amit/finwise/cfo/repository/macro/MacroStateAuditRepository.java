package org.amit.finwise.cfo.repository.macro;

import org.amit.finwise.cfo.model.macro.MacroStateAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MacroStateAuditRepository extends JpaRepository<MacroStateAuditEntry, Long> {
    List<MacroStateAuditEntry> findTop100ByOrderByCreatedAtDesc();
}
