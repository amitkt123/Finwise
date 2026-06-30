package org.amit.finwise.marketdata.provider;

public enum DataQuality {
    LIVE,       // real-time, <1 min stale
    EOD,        // end-of-day close
    ESTIMATED,  // modelled or interpolated
    STALE,      // fetched >24h ago
    MISSING     // unavailable; fallbackNote explains why
}
