package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.IndexEod;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IndexEodRepository extends JpaRepository<IndexEod, Long> {

    List<IndexEod> findByIndexNameAndTradeDateBetweenOrderByTradeDate(
            String indexName, LocalDate from, LocalDate to);

    /** Benchmark (e.g. "Nifty 50") closes over a window, name matched case-insensitively. */
    List<IndexEod> findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(
            String indexName, LocalDate from, LocalDate to);

    /** Index closes from {@code since}, ascending — the benchmark/factor read path. */
    List<IndexEod> findByIndexNameIgnoreCaseAndTradeDateGreaterThanEqualOrderByTradeDate(
            String indexName, LocalDate since);

    /** Latest close for one index (e.g. "India VIX") — DF-3 sources VIX from the index file, not Yahoo. */
    Optional<IndexEod> findTopByIndexNameIgnoreCaseOrderByTradeDateDesc(String indexName);
}
