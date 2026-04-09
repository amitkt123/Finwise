package org.amit.finwise.goal.repository;

import org.amit.finwise.goal.model.FinancialGoal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, Long> {

    @Query("SELECT g FROM FinancialGoal g WHERE g.userId = :userId AND g.status NOT IN ('ACHIEVED', 'ABANDONED') ORDER BY g.priority DESC, g.targetDate ASC")
    List<FinancialGoal> findActiveGoals(@Param("userId") String userId);

    @Query("SELECT g FROM FinancialGoal g WHERE g.userId = :userId AND g.status IN ('AT_RISK', 'OFF_TRACK') ORDER BY g.priority DESC")
    List<FinancialGoal> findAtRiskGoals(@Param("userId") String userId);

    List<FinancialGoal> findByUserId(String userId);
}
