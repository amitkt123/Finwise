package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.CorporateAction;
import org.amit.finwise.marketdata.model.CorporateActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface CorporateActionRepository extends JpaRepository<CorporateAction, Long> {

    /** Distinct ex-dates for a symbol — consulted by the gap classifier. */
    @Query("SELECT DISTINCT c.exDate FROM CorporateAction c "
            + "WHERE c.symbol = :symbol AND c.exDate IS NOT NULL")
    List<LocalDate> findExDatesBySymbol(@Param("symbol") String symbol);

    /** Price-affecting actions for one instrument, oldest first — drives adj_close. */
    List<CorporateAction> findByInstrumentIdAndActionTypeInAndExDateIsNotNullOrderByExDate(
            Long instrumentId, List<CorporateActionType> types);

    /** Instrument ids that have at least one corporate action (for full recompute). */
    @Query("SELECT DISTINCT c.instrumentId FROM CorporateAction c WHERE c.instrumentId IS NOT NULL")
    List<Long> findDistinctInstrumentIds();

    /** All corporate actions for a symbol, newest ex-date first — drives the company-view history card. */
    List<CorporateAction> findBySymbolOrderByExDateDesc(String symbol);
}
