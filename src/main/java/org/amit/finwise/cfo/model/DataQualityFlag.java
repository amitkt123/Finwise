package org.amit.finwise.cfo.model;

public enum DataQualityFlag {
    OK,
    SUSPECT_GAP,               // Large unexplained price move — likely a split or data error
    EXPECTED_CORPORATE_ACTION, // Large move on a known ex-date (split/bonus/etc) — explained, not an error
    IMPUTED;                   // Value filled in from adjacent data

    /**
     * Whether a point with this flag should be dropped from raw return-series
     * computations. Both an unexplained gap and a corporate-action level shift
     * produce a spurious one-day return, so both are excluded; adj_close carries
     * the true continuous series for consumers that need it.
     */
    public boolean isExcludedFromReturns() {
        return this == SUSPECT_GAP || this == EXPECTED_CORPORATE_ACTION;
    }
}
