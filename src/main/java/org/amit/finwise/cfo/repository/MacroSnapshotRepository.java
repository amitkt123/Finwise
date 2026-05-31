package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.MacroSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface MacroSnapshotRepository extends JpaRepository<MacroSnapshot, Long> {

    /**
     * Get the latest macro snapshot (for use in briefs/research).
     */
    Optional<MacroSnapshot> findTopByOrderBySnapshotDateDesc();

    /**
     * Get the snapshot for a specific date.
     */
    Optional<MacroSnapshot> findBySnapshotDate(LocalDate date);
}
