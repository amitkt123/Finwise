package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.ArticleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleEntityRepository extends JpaRepository<ArticleEntity, Long> {

    List<ArticleEntity> findByArticleId(Long articleId);

    boolean existsByArticleIdAndEntityTypeAndEntityIdAndSource(
            Long articleId, ArticleEntity.EntityType entityType, String entityId,
            ArticleEntity.Source source);

    /** Distinct INSTRUMENT symbols mentioned by any article in the given cluster. */
    @Query("""
            SELECT DISTINCT e.entityId
            FROM ArticleEntity e
            WHERE e.entityType = org.amit.finwise.cfo.model.ArticleEntity$EntityType.INSTRUMENT
              AND e.articleId IN (
                  SELECT n.id FROM NewsArticle n WHERE n.clusterId = :clusterId)
            """)
    List<String> findInstrumentSymbolsForCluster(@Param("clusterId") Long clusterId);
}
