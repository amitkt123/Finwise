package org.amit.finwise.marketdata.parser;

import org.amit.finwise.marketdata.parser.AmfiNavParser.NavRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the AMFI NAVAll.txt sectioned format: column-title row skipped, category
 * vs AMC header disambiguation, N.A. NAVs dropped, and section context carried
 * onto each NAV row.
 */
class AmfiNavParserTest {

    private static final String SAMPLE = """
            Scheme Code;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date

            Open Ended Schemes(Equity Scheme - Large Cap Fund)

            Aditya Birla Sun Life Mutual Fund

            119551;INF209K01VD7;INF209K01VE5;ABSL Frontline Equity Fund - Growth;512.34;06-Jun-2024
            119552;INF209K01VF2;-;ABSL Frontline Equity Fund - IDCW;98.7654;06-Jun-2024

            Axis Mutual Fund

            120503;INF846K01EW2;INF846K01EX0;Axis Bluechip Fund - Growth;65.21;06-Jun-2024

            Open Ended Schemes(Debt Scheme - Liquid Fund)

            Axis Mutual Fund

            112277;INF846K01131;INF846K01149;Axis Liquid Fund - Growth;N.A.;06-Jun-2024
            """;

    @Test
    void parsesSectionsAndDropsNaNav() {
        List<NavRecord> records = AmfiNavParser.parse(SAMPLE);

        // 4 data rows, but the N.A. NAV row is dropped -> 3 records.
        assertEquals(3, records.size());

        NavRecord frontline = byCode(records, "119551");
        assertEquals("INF209K01VD7", frontline.isinPayout());
        assertEquals("INF209K01VE5", frontline.isinReinvest());
        assertEquals(0, frontline.nav().compareTo(new BigDecimal("512.34")));
        assertEquals(LocalDate.of(2024, 6, 6), frontline.navDate());
        assertEquals("Aditya Birla Sun Life Mutual Fund", frontline.amc());
        assertTrue(frontline.category().contains("Large Cap"));

        // "-" reinvest ISIN normalises to null.
        assertNull(byCode(records, "119552").isinReinvest());

        // AMC and category both switch correctly across sections.
        NavRecord axisBlue = byCode(records, "120503");
        assertEquals("Axis Mutual Fund", axisBlue.amc());
        assertTrue(axisBlue.category().contains("Large Cap"));

        // The N.A. liquid-fund row is absent.
        assertTrue(records.stream().noneMatch(r -> r.amfiCode().equals("112277")));
    }

    private NavRecord byCode(List<NavRecord> rs, String code) {
        return rs.stream().filter(r -> r.amfiCode().equals(code)).findFirst().orElseThrow();
    }
}
