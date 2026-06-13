package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.MarketEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MarketEventRepository extends JpaRepository<MarketEvent, Long> {

    Optional<MarketEvent> findByClusterId(Long clusterId);
}
