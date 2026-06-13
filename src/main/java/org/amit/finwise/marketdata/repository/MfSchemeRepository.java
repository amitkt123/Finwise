package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.MfScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MfSchemeRepository extends JpaRepository<MfScheme, Long> {

    Optional<MfScheme> findByAmfiCode(String amfiCode);

    Optional<MfScheme> findByIsin(String isin);

    /**
     * Schemes whose category header matches any of the given fragments
     * (case-insensitive) — used to tier the NAV-history seed to equity/hybrid
     * first before the full ~40k AMFI universe.
     */
    @Query("SELECT s FROM MfScheme s WHERE LOWER(s.category) LIKE LOWER(CONCAT('%', :fragment, '%'))")
    List<MfScheme> findByCategoryContaining(@Param("fragment") String fragment);
}
