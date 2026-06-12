package org.amit.finwise.cfo.service.price;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Fixture test for the Phase 7 quarterly quoteSummary parser: statements are
 * merged by endDate across the three quarterly modules, and EPS actuals from
 * the earnings chart attach to the newest quarters by recency.
 */
class YahooQuarterlyParserTest {

    // Two quarters: end dates 2025-12-31 (1767119400) and 2026-03-31 (1774895400), IST.
    private static final String FIXTURE = """
            {"quoteSummary":{"result":[{
              "incomeStatementHistoryQuarterly":{"incomeStatementHistory":[
                {"endDate":{"raw":1774895400},"totalRevenue":{"raw":2000000},
                 "netIncome":{"raw":300000},"grossProfit":{"raw":800000}},
                {"endDate":{"raw":1767119400},"totalRevenue":{"raw":1800000},
                 "netIncome":{"raw":250000},"grossProfit":{"raw":700000}}
              ]},
              "balanceSheetHistoryQuarterly":{"balanceSheetStatements":[
                {"endDate":{"raw":1774895400},"totalAssets":{"raw":9000000},"totalLiab":{"raw":4000000}},
                {"endDate":{"raw":1767119400},"totalAssets":{"raw":8500000},"totalLiab":{"raw":3900000}}
              ]},
              "cashflowStatementHistoryQuarterly":{"cashflowStatements":[
                {"endDate":{"raw":1774895400},"totalCashFromOperatingActivities":{"raw":350000}}
              ]},
              "earnings":{"earningsChart":{"quarterly":[
                {"date":"4Q2025","actual":{"raw":12.5}},
                {"date":"1Q2026","actual":{"raw":14.0}}
              ]}}
            }],"error":null}}
            """;

    @Test
    void mergesStatementsByQuarterEndAndAttachesEps() throws Exception {
        YahooFinancePriceProvider provider = new YahooFinancePriceProvider();
        List<YahooFinancePriceProvider.QuarterlySnapshot> quarters =
                provider.parseQuarterlyResponse(FIXTURE, "TEST");

        assertEquals(2, quarters.size());

        YahooFinancePriceProvider.QuarterlySnapshot q4 = quarters.get(0); // ascending order
        assertEquals(LocalDate.of(2025, 12, 31), q4.quarterEnd);
        assertEquals(0, new BigDecimal("1800000").compareTo(q4.revenue));
        assertEquals(0, new BigDecimal("8500000").compareTo(q4.totalAssets));
        assertNull(q4.operatingCashFlow, "no cash-flow row for the older quarter");
        assertEquals(0, new BigDecimal("12.5").compareTo(q4.eps), "older EPS actual attaches to older quarter");

        YahooFinancePriceProvider.QuarterlySnapshot q1 = quarters.get(1);
        assertEquals(LocalDate.of(2026, 3, 31), q1.quarterEnd);
        assertEquals(0, new BigDecimal("2000000").compareTo(q1.revenue));
        assertEquals(0, new BigDecimal("300000").compareTo(q1.netIncome));
        assertEquals(0, new BigDecimal("350000").compareTo(q1.operatingCashFlow));
        assertEquals(0, new BigDecimal("14.0").compareTo(q1.eps), "newest EPS actual attaches to newest quarter");
    }

    @Test
    void failsOnEmptyResult() {
        YahooFinancePriceProvider provider = new YahooFinancePriceProvider();
        assertThrows(PriceDataProvider.PriceProviderException.class,
                () -> provider.parseQuarterlyResponse("{\"quoteSummary\":{\"result\":[]}}", "TEST"));
    }
}
