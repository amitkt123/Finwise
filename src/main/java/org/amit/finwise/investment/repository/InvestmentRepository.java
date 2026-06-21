package org.amit.finwise.investment.repository;

import org.amit.finwise.investment.model.Investment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface InvestmentRepository extends JpaRepository<Investment, Long> {

    @Query("SELECT i FROM Investment i WHERE i.userId = :userId AND i.isActive = true ORDER BY i.totalCost DESC")
    List<Investment> findActiveInvestments(@Param("userId") String userId);

    @Query("SELECT COALESCE(SUM(i.totalCost), 0) FROM Investment i WHERE i.userId = :userId AND i.isActive = true")
    BigDecimal totalInvestmentCost(@Param("userId") String userId);

    @Query("SELECT COALESCE(SUM(i.currentValue), 0) FROM Investment i WHERE i.userId = :userId AND i.isActive = true")
    BigDecimal totalPortfolioValue(@Param("userId") String userId);

    @Query("SELECT COALESCE(SUM(i.unrealizedGainLoss), 0) FROM Investment i WHERE i.userId = :userId AND i.isActive = true")
    BigDecimal totalUnrealizedGains(@Param("userId") String userId);

    @Query("SELECT i FROM Investment i WHERE i.userId = :userId AND i.symbol = :symbol AND i.isActive = true")
    List<Investment> findBySymbol(@Param("userId") String userId, @Param("symbol") String symbol);

    @Query("SELECT DISTINCT i.symbol FROM Investment i WHERE i.isActive = true AND i.symbol IS NOT NULL")
    List<String> findAllActiveSymbols();
}
