package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.StockFundamentals;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StockFundamentalsRepository extends JpaRepository<StockFundamentals, Long> {

    /**
     * Get the latest fundamentals snapshot for a symbol.
     */
    Optional<StockFundamentals> findTopBySymbolOrderBySnapshotDateDesc(String symbol);

    /**
     * Get fundamentals history for a symbol within a date range.
     * Used for computing valuation z-scores.
     */
    List<StockFundamentals> findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            String symbol, LocalDate fromDate, LocalDate toDate);

    /**
     * Get the latest fundamentals for a list of symbols.
     */
    @Query("SELECT f FROM StockFundamentals f WHERE f.symbol IN :symbols " +
           "AND (f.symbol, f.snapshotDate) IN " +
           "(SELECT f2.symbol, MAX(f2.snapshotDate) FROM StockFundamentals f2 " +
           "WHERE f2.symbol IN :symbols GROUP BY f2.symbol)")
    List<StockFundamentals> findLatestBySymbols(List<String> symbols);
}
