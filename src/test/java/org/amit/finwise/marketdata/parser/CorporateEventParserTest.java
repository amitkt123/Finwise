package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.marketdata.model.CorporateEventType;
import org.amit.finwise.marketdata.parser.CorporateEventParser.EventRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorporateEventParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/marketdata/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesForwardEventCalendar() throws IOException {
        List<EventRecord> records =
                CorporateEventParser.parseEventCalendar(fixture("event_calendar_sample.json"), mapper);
        assertFalse(records.isEmpty());

        EventRecord first = records.get(0);
        assertEquals("FINKURVE", first.symbol());
        assertEquals(LocalDate.of(2026, 6, 15), first.eventDate());
        assertEquals(CorporateEventType.FUND_RAISING, first.eventType());
        assertNotNull(first.payload());
        assertTrue(first.payload().contains("FINKURVE"));

        // "Financial Results" purpose maps to RESULTS
        assertTrue(records.stream().anyMatch(r -> r.eventType() == CorporateEventType.RESULTS));
    }

    @Test
    void parsesHistoricalEarnings() throws IOException {
        List<EventRecord> records =
                CorporateEventParser.parseEarningsHistory(fixture("earning_dates_sample.json"), mapper);
        assertFalse(records.isEmpty());
        assertTrue(records.stream().anyMatch(r -> r.eventType() == CorporateEventType.RESULTS));
        assertTrue(records.stream().anyMatch(r -> r.eventType() == CorporateEventType.DIVIDEND));
        assertTrue(records.stream().anyMatch(r -> r.eventType() == CorporateEventType.BONUS));
        assertTrue(records.stream().anyMatch(r -> r.eventType() == CorporateEventType.SPLIT));
        // ISO date yyyy-MM-dd is parsed
        assertTrue(records.stream().allMatch(r -> r.eventDate() != null));
    }

    @Test
    void mapsEventTypeStrings() {
        assertEquals(CorporateEventType.RESULTS, CorporateEventParser.fromEventType("stock_results"));
        assertEquals(CorporateEventType.SPLIT, CorporateEventParser.fromEventType("stock_split"));
        assertEquals(CorporateEventType.RESULTS, CorporateEventParser.fromPurpose("Financial Results/Dividend"));
        assertEquals(CorporateEventType.BOARD_MEETING, CorporateEventParser.fromPurpose("Other business matters"));
    }
}
