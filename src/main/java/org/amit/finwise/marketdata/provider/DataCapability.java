package org.amit.finwise.marketdata.provider;

public enum DataCapability {
    REAL_TIME_QUOTE,
    EOD_PRICE,
    HISTORICAL_OHLCV,
    FUNDAMENTALS,
    OPTION_CHAIN,
    PORTFOLIO_SYNC,
    MACRO_GLOBAL,       // FRED: DXY, crude, VIX, Fed rate
    MACRO_INDIA,        // RBI DBIE: repo, CRR, credit growth
    CORPORATE_FILINGS,  // BSE XBRL: promoter pledge, shareholding
    ANNOUNCEMENTS,      // NSE: board meetings, results calendar
    INSIDER_TRADES,     // SEBI disclosures
    WORLD_BANK          // GDP, CPI, current account
}
