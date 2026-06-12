package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.QuarterlyFundamentals;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface QuarterlyFundamentalsRepository extends JpaRepository<QuarterlyFundamentals, Long> {

    /**
     * Fetch all quarterly records for a symbol, sorted by quarter_end descending.
     */
    List<QuarterlyFundamentals> findBySymbolOrderByQuarterEndDesc(String symbol);

    /**
     * Existence check for upsert on the (symbol, quarter_end) natural key.
     */
    boolean existsBySymbolAndQuarterEnd(String symbol, LocalDate quarterEnd);

    /**
     * Fetch all quarters after a given date (for trend analysis).
     */
    @Query("SELECT q FROM QuarterlyFundamentals q WHERE q.symbol = ?1 AND q.quarterEnd >= ?2 " +
           "ORDER BY q.quarterEnd ASC")
    List<QuarterlyFundamentals> findSinceQuarter(String symbol, LocalDate minQuarterEnd);
}
