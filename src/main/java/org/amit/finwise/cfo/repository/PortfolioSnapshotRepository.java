package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PortfolioSnapshotRepository extends JpaRepository<PortfolioSnapshot, Long> {

    Optional<PortfolioSnapshot> findTopByUserIdOrderBySnapshotTimeDesc(String userId);

    @Query("SELECT s FROM PortfolioSnapshot s WHERE s.userId = :userId AND s.snapshotTime >= :since ORDER BY s.snapshotTime DESC")
    List<PortfolioSnapshot> findRecentSnapshots(@Param("userId") String userId, @Param("since") LocalDateTime since);

    /** Snapshot closest to (but strictly before) a given time — for trend comparison */
    Optional<PortfolioSnapshot> findTopByUserIdAndSnapshotTimeBeforeOrderBySnapshotTimeDesc(
            String userId, LocalDateTime before);
}
