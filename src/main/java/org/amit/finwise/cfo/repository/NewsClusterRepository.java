package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.NewsCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NewsClusterRepository extends JpaRepository<NewsCluster, Long> {

    /**
     * Clusters that opened within a window — the candidate set for the nightly
     * outcome-enrichment job (firstSeen old enough for at least the 1d horizon,
     * recent enough that the 20d horizon may still be filling in).
     */
    @Query("SELECT c FROM NewsCluster c WHERE c.firstSeen BETWEEN :from AND :to")
    List<NewsCluster> findOpenedBetween(@Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);
}
