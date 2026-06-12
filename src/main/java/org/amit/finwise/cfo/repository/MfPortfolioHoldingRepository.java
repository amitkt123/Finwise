package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.MfPortfolioHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MfPortfolioHoldingRepository extends JpaRepository<MfPortfolioHolding, Long> {

    /** Most recent disclosure date on file for a scheme, if any. */
    @Query("SELECT MAX(h.asOf) FROM MfPortfolioHolding h WHERE h.schemeCode = :schemeCode")
    LocalDate latestAsOf(@Param("schemeCode") String schemeCode);

    /** All constituents of a scheme at a specific disclosure date. */
    List<MfPortfolioHolding> findBySchemeCodeAndAsOf(String schemeCode, LocalDate asOf);

    /** Replace any existing rows for a scheme/date before re-importing that snapshot. */
    @Transactional
    void deleteBySchemeCodeAndAsOf(String schemeCode, LocalDate asOf);
}
