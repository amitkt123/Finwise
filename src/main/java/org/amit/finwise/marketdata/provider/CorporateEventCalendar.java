package org.amit.finwise.marketdata.provider;

import java.time.LocalDate;
import java.util.List;

public record CorporateEventCalendar(
    String symbol,
    List<CorporateEvent> upcomingEvents
) {
    public record CorporateEvent(String eventType, LocalDate eventDate, String description) {}
}
