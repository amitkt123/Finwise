package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.EodPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EodPriceRepository extends JpaRepository<EodPrice, Long> {

    List<EodPrice> findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(
            Long instrumentId, LocalDate from, LocalDate to);

    long countByTradeDate(LocalDate tradeDate);

    @Query("SELECT MIN(e.tradeDate) FROM EodPrice e")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(e.tradeDate) FROM EodPrice e")
    Optional<LocalDate> findMaxTradeDate();
}
