package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.NewsArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    @Query("SELECT n FROM NewsArticle n WHERE n.publishedDate >= :since ORDER BY n.relevanceScore DESC, n.publishedDate DESC")
    List<NewsArticle> findRecentByRelevance(@Param("since") LocalDate since);

    @Query("SELECT n FROM NewsArticle n WHERE n.publishedDate >= :since AND n.relevanceLevel = 'HIGH' ORDER BY n.publishedDate DESC")
    List<NewsArticle> findHighRelevanceNews(@Param("since") LocalDate since);

    boolean existsByUrlAndPublishedDate(String url, LocalDate publishedDate);

    /**
     * Batch existence check — returns only the URLs from the given list that are
     * already stored. Used by NewsAggregatorService to replace per-article
     * existsByUrlAndPublishedDate calls (N+1 → 1 query per feed).
     */
    @Query("SELECT n.url FROM NewsArticle n WHERE n.url IN :urls")
    List<String> findExistingUrlsIn(@Param("urls") List<String> urls);

    // ── Queries for personalized scoring ─────────────────────────────────────

    /**
     * Used by InvestorBehaviorService to detect panic sells:
     * find HIGH relevance NEGATIVE articles mentioning a specific symbol
     * within a date window (typically 5 days before a SELL transaction).
     */
    @Query("SELECT n FROM NewsArticle n WHERE n.publishedDate BETWEEN :from AND :to " +
           "AND n.relatedSymbols LIKE %:symbol% " +
           "AND n.sentiment = 'NEGATIVE' AND n.relevanceLevel = 'HIGH'")
    List<NewsArticle> findNegativeHighRelevanceForSymbol(
            @Param("symbol") String symbol,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Used by MarketContextService to compute isVolatileMarket:
     * count of HIGH relevance NEGATIVE articles since a given date.
     */
    @Query("SELECT n FROM NewsArticle n WHERE n.publishedDate >= :since " +
           "AND n.relevanceLevel = 'HIGH' AND n.sentiment = 'NEGATIVE' " +
           "ORDER BY n.publishedDate DESC")
    List<NewsArticle> findHighRelevanceNegativeSince(@Param("since") LocalDate since);

    /**
     * Used by MarketContextService for 7-day sentiment aggregation
     * and sector sentiment computation.
     */
    @Query("SELECT n FROM NewsArticle n WHERE n.publishedDate >= :since " +
           "ORDER BY n.relevanceScore DESC, n.publishedDate DESC")
    List<NewsArticle> findRecentForSentimentAggregation(@Param("since") LocalDate since);
}
