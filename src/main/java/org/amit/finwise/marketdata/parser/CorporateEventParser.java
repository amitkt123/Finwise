package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.CorporateEventType;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the two event-calendar sources into a common event-shaped record:
 *
 *   Forward NSE event calendar (/api/event-calendar):
 *     {symbol, company, purpose, bm_desc, date:"15-Jun-2026"}
 *   Historical earnings import (earning_dates.json):
 *     {date:"2022-04-25", stock_name, trading_symbol, event_type, description, ...}
 *
 * The full source object is preserved verbatim as the JSONB payload.
 */
@Slf4j
public final class CorporateEventParser {

    private static final DateTimeFormatter NSE_DATE = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private CorporateEventParser() {}

    public record EventRecord(
            String symbol, String companyName, CorporateEventType eventType,
            LocalDate eventDate, String description, String payload) {}

    /** Parse the forward NSE event-calendar JSON array. */
    public static List<EventRecord> parseEventCalendar(String json, ObjectMapper mapper) {
        List<EventRecord> out = new ArrayList<>();
        for (JsonNode n : readArray(json, mapper)) {
            String symbol = text(n, "symbol");
            LocalDate date = isoOrNse(text(n, "date"));
            if (symbol == null || date == null) continue;
            String purpose = text(n, "purpose");
            String desc = text(n, "bm_desc");
            out.add(new EventRecord(symbol, text(n, "company"), fromPurpose(purpose),
                    date, desc != null ? desc : purpose, n.toString()));
        }
        return out;
    }

    /** Parse the one-off historical earnings JSON array. */
    public static List<EventRecord> parseEarningsHistory(String json, ObjectMapper mapper) {
        List<EventRecord> out = new ArrayList<>();
        for (JsonNode n : readArray(json, mapper)) {
            String symbol = text(n, "trading_symbol");
            LocalDate date = isoOrNse(text(n, "date"));
            if (symbol == null || date == null) continue;
            out.add(new EventRecord(symbol, text(n, "stock_name"),
                    fromEventType(text(n, "event_type")), date,
                    text(n, "description"), n.toString()));
        }
        return out;
    }

    static CorporateEventType fromPurpose(String purpose) {
        if (purpose == null) return CorporateEventType.BOARD_MEETING;
        String p = purpose.toLowerCase(Locale.ENGLISH);
        if (p.contains("result")) return CorporateEventType.RESULTS;
        if (p.contains("split")) return CorporateEventType.SPLIT;
        if (p.contains("bonus")) return CorporateEventType.BONUS;
        if (p.contains("dividend")) return CorporateEventType.DIVIDEND;
        if (p.contains("fund rais")) return CorporateEventType.FUND_RAISING;
        return CorporateEventType.BOARD_MEETING;
    }

    static CorporateEventType fromEventType(String eventType) {
        if (eventType == null) return CorporateEventType.OTHER;
        return switch (eventType.toLowerCase(Locale.ENGLISH)) {
            case "stock_results" -> CorporateEventType.RESULTS;
            case "dividend" -> CorporateEventType.DIVIDEND;
            case "bonus" -> CorporateEventType.BONUS;
            case "stock_split" -> CorporateEventType.SPLIT;
            default -> CorporateEventType.OTHER;
        };
    }

    private static List<JsonNode> readArray(String json, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr = root.isArray() ? root : root.path("data");
            List<JsonNode> nodes = new ArrayList<>();
            if (arr.isArray()) arr.forEach(nodes::add);
            return nodes;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable event JSON: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return (s.isEmpty() || s.equals("-")) ? null : s;
    }

    private static LocalDate isoOrNse(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);                 // yyyy-MM-dd
        } catch (RuntimeException ignored) {
            try {
                return LocalDate.parse(s, NSE_DATE);    // dd-MMM-yyyy
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
