package org.amit.finwise.marketdata.repository;

import org.amit.finwise.marketdata.model.CorporateEvent;
import org.amit.finwise.marketdata.model.CorporateEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CorporateEventRepository extends JpaRepository<CorporateEvent, Long> {

    /** All events for a symbol, most recent first. */
    List<CorporateEvent> findBySymbolOrderByEventDateDesc(String symbol);

    /** Forward calendar: events on or after {@code from}, soonest first. */
    List<CorporateEvent> findBySymbolAndEventDateGreaterThanEqualOrderByEventDate(
            String symbol, LocalDate from);

    /** Past events of a given type, newest first — the event-study sample. */
    List<CorporateEvent> findBySymbolAndEventTypeAndEventDateLessThanOrderByEventDateDesc(
            String symbol, CorporateEventType eventType, LocalDate before);
}
