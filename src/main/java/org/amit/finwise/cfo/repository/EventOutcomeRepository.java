package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.EventOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventOutcomeRepository extends JpaRepository<EventOutcome, Long> {

    Optional<EventOutcome> findByClusterIdAndInstrumentIdAndHorizon(
            Long clusterId, Long instrumentId, EventOutcome.Horizon horizon);

    List<EventOutcome> findByClusterId(Long clusterId);

    @Query("SELECT o FROM EventOutcome o WHERE o.clusterId IN :clusterIds")
    List<EventOutcome> findByClusterIdIn(@Param("clusterIds") List<Long> clusterIds);
}
