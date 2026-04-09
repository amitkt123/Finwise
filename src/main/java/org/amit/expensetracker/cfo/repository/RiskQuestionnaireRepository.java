package org.amit.expensetracker.cfo.repository;

import org.amit.expensetracker.cfo.model.RiskQuestionnaire;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskQuestionnaireRepository extends JpaRepository<RiskQuestionnaire, Long> {

    Optional<RiskQuestionnaire> findByUserId(String userId);

    boolean existsByUserId(String userId);
}
