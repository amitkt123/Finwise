package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.MarketDeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface MarketDealRepository extends JpaRepository<MarketDeal, Long> {

    List<MarketDeal> findBySymbolOrderByDealDateDesc(String symbol);

    List<MarketDeal> findByDealDateOrderBySymbol(LocalDate dealDate);

    long countByDealType(MarketDeal.DealType dealType);
}
