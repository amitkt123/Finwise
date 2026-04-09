package org.amit.expensetracker.cfo.repository;

import org.amit.expensetracker.cfo.model.StockPriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockPriceHistoryRepository extends JpaRepository<StockPriceHistory, Long> {

    /** Latest price entry for a symbol */
    Optional<StockPriceHistory> findTopBySymbolOrderByPriceDateDesc(String symbol);

    /** Last N days of price history for a symbol */
    @Query("SELECT s FROM StockPriceHistory s WHERE s.symbol = :symbol AND s.priceDate >= :since ORDER BY s.priceDate DESC")
    List<StockPriceHistory> findRecentBySymbol(@Param("symbol") String symbol, @Param("since") LocalDate since);

    /** All price history for a symbol ordered newest-first */
    List<StockPriceHistory> findBySymbolOrderByPriceDateDesc(String symbol);

    /** Symbols currently on consecutive circuits (for anomaly detection) */
    @Query("SELECT s FROM StockPriceHistory s WHERE s.symbol IN :symbols AND s.priceDate = :date")
    List<StockPriceHistory> findBySymbolsAndDate(@Param("symbols") List<String> symbols, @Param("date") LocalDate date);

    boolean existsBySymbolAndPriceDate(String symbol, LocalDate priceDate);

    /** Latest price entries for multiple symbols (for portfolio volatility calc) */
    @Query("SELECT s FROM StockPriceHistory s WHERE s.symbol IN :symbols AND s.priceDate >= :since ORDER BY s.symbol, s.priceDate DESC")
    List<StockPriceHistory> findRecentBySymbols(@Param("symbols") List<String> symbols, @Param("since") LocalDate since);
}
