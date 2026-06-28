package org.amit.finwise.cfo.repository.macro;

import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry.SignalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyQuantSignalRepository extends JpaRepository<PolicyQuantSignalQueueEntry, Long> {
    Page<PolicyQuantSignalQueueEntry> findByStatus(SignalStatus status, Pageable pageable);
    List<PolicyQuantSignalQueueEntry> findByStatusOrderByCreatedAtDesc(SignalStatus status);
}
