package org.amit.expensetracker.cfo.repository;

import org.amit.expensetracker.cfo.model.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByUserIdAndServiceAndIsActiveTrue(String userId, String service);
}
