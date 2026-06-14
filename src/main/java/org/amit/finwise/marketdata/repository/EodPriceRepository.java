package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.EodPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EodPriceRepository extends JpaRepository<EodPrice, Long> {

    List<EodPrice> findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(
            Long instrumentId, LocalDate from, LocalDate to);

    /** Backbone series for one symbol from {@code since}, ascending — the analytics read path. */
    List<EodPrice> findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate(
            String symbol, LocalDate since);

    /** Backbone series for many symbols in one query (risk/factor batch reads). */
    @Query("SELECT e FROM EodPrice e WHERE e.symbol IN :symbols AND e.tradeDate >= :since "
            + "ORDER BY e.symbol, e.tradeDate")
    List<EodPrice> findBySymbolsSince(@Param("symbols") List<String> symbols,
                                      @Param("since") LocalDate since);

    long countByTradeDate(LocalDate tradeDate);

    @Query("SELECT MIN(e.tradeDate) FROM EodPrice e")
    Optional<LocalDate> findMinTradeDate();

    @Query("SELECT MAX(e.tradeDate) FROM EodPrice e")
    Optional<LocalDate> findMaxTradeDate();
}
