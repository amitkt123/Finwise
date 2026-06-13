package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.amit.finwise.marketdata.model.MarketDeal;
import org.amit.finwise.marketdata.parser.FilingsParsers.AnnouncementRecord;
import org.amit.finwise.marketdata.parser.FilingsParsers.DealRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defensive-parsing tests for the NSE filings feeds: a {@code data}-wrapped array
 * vs a bare array, NSE's BD_* deal field names, and the BUY/SELL side mapping.
 */
class FilingsParsersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesAnnouncementsFromDataWrappedArray() {
        String json = """
                {"data":[
                  {"symbol":"TCS","sm_name":"Tata Consultancy Services Ltd",
                   "desc":"Financial Results","attchmntText":"Audited results for Q4",
                   "an_dt":"25-Apr-2026 18:30:00","attchmntFile":"https://nsearchives/x.pdf"}
                ]}
                """;
        List<AnnouncementRecord> recs = FilingsParsers.parseAnnouncements(json, mapper);
        assertEquals(1, recs.size());
        AnnouncementRecord a = recs.get(0);
        assertEquals("TCS", a.symbol());
        assertEquals("Financial Results", a.subject());
        assertEquals(2026, a.broadcastAt().getYear());
        assertEquals(18, a.broadcastAt().getHour());
        assertTrue(a.attachmentUrl().endsWith("x.pdf"));
    }

    @Test
    void parsesDealsWithNseFieldNamesAndSide() {
        String json = """
                [
                  {"BD_DT_DATE":"02-Jun-2026","BD_SYMBOL":"IDEA","BD_SCRIP_NAME":"Vodafone Idea",
                   "BD_CLIENT_NAME":"Some FII","BD_BUY_SELL":"BUY","BD_QTY_TRD":"1,250,000","BD_TP_WATP":"12.45"},
                  {"BD_DT_DATE":"02-Jun-2026","BD_SYMBOL":"YESBANK","BD_SCRIP_NAME":"Yes Bank",
                   "BD_CLIENT_NAME":"Some Fund","BD_BUY_SELL":"SELL","BD_QTY_TRD":"800000","BD_TP_WATP":"22.10"}
                ]
                """;
        List<DealRecord> recs = FilingsParsers.parseDeals(json, mapper);
        assertEquals(2, recs.size());

        DealRecord buy = recs.get(0);
        assertEquals(LocalDate.of(2026, 6, 2), buy.dealDate());
        assertEquals("IDEA", buy.symbol());
        assertEquals(MarketDeal.Side.BUY, buy.side());
        assertEquals(1_250_000L, buy.quantity());
        assertEquals(0, buy.price().compareTo(new java.math.BigDecimal("12.45")));

        assertEquals(MarketDeal.Side.SELL, recs.get(1).side());
    }

    @Test
    void toleratesMissingArrayAndUnknownFields() {
        assertTrue(FilingsParsers.parseAnnouncements("{\"foo\":1}", mapper).isEmpty());
        assertTrue(FilingsParsers.parseDeals("[]", mapper).isEmpty());
    }
}
