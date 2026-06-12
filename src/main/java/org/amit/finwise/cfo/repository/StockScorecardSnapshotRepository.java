package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.StockScorecardSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockScorecardSnapshotRepository extends JpaRepository<StockScorecardSnapshot, Long> {
    Optional<StockScorecardSnapshot> findTopBySymbolOrderByScorecardDateDescCreatedAtDesc(String symbol);

    List<StockScorecardSnapshot> findTop20BySymbolOrderByScorecardDateDescCreatedAtDesc(String symbol);
}
