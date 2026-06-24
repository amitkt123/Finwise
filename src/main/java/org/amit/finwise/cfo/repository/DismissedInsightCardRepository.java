package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.DismissedInsightCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Set;

@Repository
public interface DismissedInsightCardRepository extends JpaRepository<DismissedInsightCard, Long> {
    Set<DismissedInsightCard> findByUserId(String userId);
    boolean existsByUserIdAndCardId(String userId, String cardId);
}
