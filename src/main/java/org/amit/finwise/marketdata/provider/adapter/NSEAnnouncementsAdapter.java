package org.amit.finwise.marketdata.provider.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Forward-looking NSE event calendar (board meetings with stated purpose) for a symbol.
 * Routed through {@link NseApiClient}, which handles the cookie warm-up NSE requires —
 * a bare RestClient call with just a User-Agent header gets 401/403'd.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NSEAnnouncementsAdapter implements MarketFeedProvider {

    private final NseApiClient nseApiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override public String name() { return "nse-announcements"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.ANNOUNCEMENTS; }
    @Override public boolean isHealthy() { return true; }

    public DataEnvelope<CorporateEventCalendar> fetchUpcomingEvents(String symbol) {
        try {
            Optional<String> body = nseApiClient.fetchEventCalendar(symbol);
            if (body.isEmpty()) {
                return DataEnvelope.missing(name(), "NSE event calendar unavailable for " + symbol);
            }

            Map<?, ?> response = objectMapper.readValue(body.get(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = response != null
                ? (List<Map<String, Object>>) response.get("data") : List.of();
            if (events == null) events = List.of();

            List<CorporateEventCalendar.CorporateEvent> mapped = events.stream()
                .map(e -> new CorporateEventCalendar.CorporateEvent(
                    (String) e.getOrDefault("purpose", ""),
                    parseDate((String) e.getOrDefault("date", "")),
                    (String) e.getOrDefault("description", "")
                )).toList();

            return DataEnvelope.of(new CorporateEventCalendar(symbol, mapped), name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[NSEAnnouncements] fetch failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }

    private LocalDate parseDate(String raw) {
        try { return LocalDate.parse(raw.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
