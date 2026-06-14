package org.amit.finwise.marketdata.parser;

import org.amit.finwise.marketdata.parser.BhavcopyParsers.EodRecord;
import org.amit.finwise.marketdata.parser.BhavcopyParsers.IndexRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser tests against real NSE file excerpts (fixtures captured 2026-06-13
 * from archives.nseindia.com / nsearchives.nseindia.com).
 */
class BhavcopyParsersTest {

    private String fixture(String name) throws IOException {
        try (var in = getClass().getResourceAsStream("/marketdata/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesOldCmFormat_keepsOnlyEqAndBe() throws IOException {
        List<EodRecord> records = BhavcopyParsers.parseOldCm(fixture("cm_old_sample.csv"));

        // fixture: 4 EQ + 1 BE kept, 2 GS dropped
        assertEquals(5, records.size());
        assertTrue(records.stream().noneMatch(r -> r.series().equals("GS")));

        EodRecord first = records.getFirst();
        assertEquals("20MICRONS", first.symbol());
        assertEquals("INE144J01027", first.isin());
        assertEquals(LocalDate.of(2021, 4, 1), first.tradeDate());
        assertEquals(new BigDecimal("35.4"), first.close());
        assertEquals(new BigDecimal("35.15"), first.prevClose());
        assertEquals(33682L, first.volume());
        assertEquals(384L, first.trades());
        assertNull(first.name()); // old format carries no company name

        EodRecord be = records.get(4);
        assertEquals("AAKASH", be.symbol());
        assertEquals("BE", be.series());
    }

    @Test
    void parsesUdiffFormat_byHeaderName() throws IOException {
        List<EodRecord> records = BhavcopyParsers.parseUdiff(fixture("cm_udiff_sample.csv"));

        // fixture: 4 EQ + 1 BE kept, N6/ST dropped
        assertEquals(5, records.size());

        EodRecord first = records.getFirst();
        assertEquals("UNOMINDA", first.symbol());
        assertEquals("INE405E01023", first.isin());
        assertEquals("UNO MINDA LIMITED", first.name());
        assertEquals(LocalDate.of(2025, 1, 10), first.tradeDate());
        assertEquals(new BigDecimal("1097.65"), first.close());
        assertEquals(849726L, first.volume());
        assertEquals(60390L, first.trades());
    }

    @Test
    void parsesIndexCloseAll_withMissingValuationFields() throws IOException {
        List<IndexRecord> records = BhavcopyParsers.parseIndexCloseAll(fixture("ind_close_all_sample.csv"));

        assertEquals(5, records.size());
        IndexRecord nifty = records.getFirst();
        assertEquals("Nifty 50", nifty.indexName());
        assertEquals(LocalDate.of(2025, 1, 10), nifty.tradeDate());
        assertEquals(new BigDecimal("23431.5"), nifty.close());
        assertEquals(new BigDecimal("21.59"), nifty.pe());
        assertEquals(new BigDecimal("3.49"), nifty.pb());
        // NSE renders small negatives without a leading zero ("-.4")
        assertEquals(0, new BigDecimal("-0.4").compareTo(nifty.changePct()));
    }

    @Test
    void indexRowsWithDashValuations_parseAsNull() {
        String csv = """
                Index Name,Index Date,Open Index Value,High Index Value,Low Index Value,Closing Index Value,Points Change,Change(%),Volume,Turnover (Rs. Cr.),P/E,P/B,Div Yield
                India VIX,10-01-2025,14.5,15.2,14.1,14.77,0.5,3.5,-,-,-,-,-
                """;
        List<IndexRecord> records = BhavcopyParsers.parseIndexCloseAll(csv);
        assertEquals(1, records.size());
        IndexRecord vix = records.getFirst();
        assertEquals("India VIX", vix.indexName());
        assertEquals(new BigDecimal("14.77"), vix.close());
        assertNull(vix.pe());
        assertNull(vix.volume());
    }

    @Test
    void parsesSecBhavdataFull_spacePaddedFields() {
        // real row shape: fields padded with spaces, dates "01-Apr-2021"
        String csv = """
                SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE, LOW_PRICE, LAST_PRICE, CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS, NO_OF_TRADES, DELIV_QTY, DELIV_PER
                20MICRONS, EQ, 01-Apr-2021, 35.15, 35.40, 35.90, 34.50, 35.70, 35.40, 35.16, 33682, 11.84, 384, 18390, 54.60
                NODELIV, EQ, 01-Apr-2021, 10, 10, 10, 10, 10, 10, 10, 100, 0.01, 5, -, -
                SOMEBOND, GS, 01-Apr-2021, 84, 84, 84, 84, 84, 84, 84, 5, 0.004, 1, 5, 100
                """;
        var records = BhavcopyParsers.parseSecBhavdataFull(csv);

        assertEquals(2, records.size()); // GS dropped
        var first = records.getFirst();
        assertEquals("20MICRONS", first.symbol());
        assertEquals(LocalDate.of(2021, 4, 1), first.tradeDate());
        assertEquals(new BigDecimal("35.16"), first.vwap());
        assertEquals(18390L, first.delivQty());
        assertEquals(new BigDecimal("54.60"), first.delivPct());

        var noDeliv = records.get(1);
        assertNull(noDeliv.delivQty());
        assertNull(noDeliv.delivPct());
    }

    @Test
    void splitCsv_honoursQuotedCommas() {
        String[] f = BhavcopyParsers.splitCsv("A,\"B, Inc\",C");
        assertArrayEquals(new String[]{"A", "B, Inc", "C"}, f);
    }

    @Test
    void malformedRowsAreSkippedNotFatal() {
        String csv = """
                SYMBOL,SERIES,OPEN,HIGH,LOW,CLOSE,LAST,PREVCLOSE,TOTTRDQTY,TOTTRDVAL,TIMESTAMP,TOTALTRADES,ISIN,
                GOOD,EQ,10,11,9,10.5,10.5,10,100,1050,01-APR-2021,5,INE000A01001,
                BAD,EQ,xx,11,9,10.5,10.5,10,100,1050,01-APR-2021,5,INE000A01002,
                """;
        List<EodRecord> records = BhavcopyParsers.parseOldCm(csv);
        assertEquals(1, records.size());
        assertEquals("GOOD", records.getFirst().symbol());
    }
}
