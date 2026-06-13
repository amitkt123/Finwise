package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.ShareholdingPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShareholdingPatternRepository extends JpaRepository<ShareholdingPattern, Long> {

    List<ShareholdingPattern> findBySymbolOrderByPeriodEndedDesc(String symbol);

    Optional<ShareholdingPattern> findTopBySymbolOrderByPeriodEndedDesc(String symbol);
}
