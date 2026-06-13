package org.amit.finwise.cfo.model;

/**
 * The macro time-series the system tracks, each with the metadata that drives
 * its ingestion sanity-band and per-series staleness SLA (DF-3).
 *
 * <p>A {@code macro_series} row is one observation: (code, obs_date, value).
 * Slope/derived signals are computed on read, never stored.
 *
 * <p>{@code slaMaxAgeDays} is how old the most recent observation may be before
 * {@link org.amit.finwise.cfo.service.macro.MacroSeriesService} reports the
 * series as STALE — dailies get a few-day grace for weekends/holidays, monthly
 * prints (CPI/IIP/WPI) get the publication lag (~6 weeks) plus a buffer, and
 * policy rates change rarely so they get months.
 */
public enum MacroSeriesCode {

    // ── FBIL G-sec par yield curve (daily) ──────────────────────────────────
    GSEC_3M ("FBIL", 5,   0.0, 15.0),
    GSEC_1Y ("FBIL", 5,   0.0, 15.0),
    GSEC_5Y ("FBIL", 5,   0.0, 15.0),
    GSEC_10Y("FBIL", 5,   0.0, 15.0),

    // ── RBI policy rates (changed only at MPC meetings) ─────────────────────
    REPO_RATE("RBI", 120, 1.0, 15.0),
    SDF_RATE ("RBI", 120, 1.0, 15.0),
    MSF_RATE ("RBI", 120, 1.0, 15.0),
    CRR      ("RBI", 120, 0.0, 12.0),

    // ── MOSPI prints via data.gov.in (monthly) ──────────────────────────────
    CPI_HEADLINE("MOSPI", 75,  -5.0, 30.0),
    CPI_CORE    ("MOSPI", 75,  -5.0, 30.0),
    IIP         ("MOSPI", 90, -50.0, 50.0),
    WPI         ("MOSPI", 75, -15.0, 40.0),

    // ── Currency & volatility (daily, from DF-1 feeds, not Yahoo) ────────────
    USD_INR  ("FBIL",       5, 30.0, 200.0),
    INDIA_VIX("NSE_INDEX",  5,  2.0, 120.0);

    private final String defaultSource;
    private final int slaMaxAgeDays;
    private final double sanityMin;
    private final double sanityMax;

    MacroSeriesCode(String defaultSource, int slaMaxAgeDays, double sanityMin, double sanityMax) {
        this.defaultSource = defaultSource;
        this.slaMaxAgeDays = slaMaxAgeDays;
        this.sanityMin = sanityMin;
        this.sanityMax = sanityMax;
    }

    public String defaultSource()  { return defaultSource; }
    public int slaMaxAgeDays()     { return slaMaxAgeDays; }
    public double sanityMin()      { return sanityMin; }
    public double sanityMax()      { return sanityMax; }

    /** True when {@code value} falls inside the series' plausible band. */
    public boolean isSane(double value) {
        return value >= sanityMin && value <= sanityMax;
    }
}
