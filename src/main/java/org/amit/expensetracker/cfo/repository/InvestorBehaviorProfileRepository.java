package org.amit.expensetracker.cfo.repository;

import org.amit.expensetracker.cfo.model.InvestorBehaviorProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface InvestorBehaviorProfileRepository extends JpaRepository<InvestorBehaviorProfile, Long> {

    Optional<InvestorBehaviorProfile> findByUserId(String userId);

    @Query("SELECT p.computedAt FROM InvestorBehaviorProfile p WHERE p.userId = :userId")
    Optional<LocalDateTime> findComputedAtByUserId(@Param("userId") String userId);
}
