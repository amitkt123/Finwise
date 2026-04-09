package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findByUserIdOrderByTransactionDateDesc(String userId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.transactionDate BETWEEN :start AND :end ORDER BY t.transactionDate DESC")
    List<Transaction> findByUserIdAndDateRange(@Param("userId") String userId,
                                               @Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    Optional<Transaction> findByDedupHash(String dedupHash);

    boolean existsByDedupHash(String dedupHash);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId AND t.transactionDate >= :since ORDER BY t.transactionDate DESC")
    List<Transaction> findRecentTransactions(@Param("userId") String userId, @Param("since") LocalDate since);

    /**
     * All BUY and SELL transactions for a user that have a non-null symbol,
     * sorted oldest-first. Used by InvestorBehaviorService for FIFO analysis.
     */
    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId " +
           "AND t.symbol IS NOT NULL " +
           "AND t.transactionType IN :types " +
           "ORDER BY t.transactionDate ASC, t.id ASC")
    List<Transaction> findBuySellTransactionsAsc(@Param("userId") String userId,
                                                 @Param("types") java.util.Collection<Transaction.TransactionType> types);

    default List<Transaction> findBuySellTransactionsAsc(String userId) {
        return findBuySellTransactionsAsc(userId,
                java.util.List.of(Transaction.TransactionType.BUY, Transaction.TransactionType.SELL));
    }
}
