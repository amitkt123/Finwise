package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Yield-curve signals from configured G-sec yields (Phase 6c).
 *
 * Tenors come from config (cfo.macro.gsec.3m/1y/5y/10y; the 10Y falls back to
 * the pre-existing cfo.macro.gsec-yield-10y key used for the Sharpe R_f).
 * An FBIL scraper is a later hardening task — config is the source of truth.
 *
 * Slope = 10Y − 3M in percentage points (0.5 = 50bp):
 *   INVERTED  slope < 0
 *   FLAT      0 ≤ slope ≤ 0.5
 *   NORMAL    0.5 < slope ≤ 1.5
 *   STEEP     slope > 1.5
 *
 * Missing tenors yield null slopes and UNKNOWN shape — never fabricated zeros.
 */
@Slf4j
@Service
public class YieldCurveService {

    private final BigDecimal gsec3m;
    private final BigDecimal gsec1y;
    private final BigDecimal gsec5y;
    private final BigDecimal gsec10y;

    public YieldCurveService(
            @Value("${cfo.macro.gsec.3m:#{null}}") BigDecimal gsec3m,
            @Value("${cfo.macro.gsec.1y:#{null}}") BigDecimal gsec1y,
            @Value("${cfo.macro.gsec.5y:#{null}}") BigDecimal gsec5y,
            @Value("${cfo.macro.gsec.10y:${cfo.macro.gsec-yield-10y:#{null}}}") BigDecimal gsec10y) {
        this.gsec3m = gsec3m;
        this.gsec1y = gsec1y;
        this.gsec5y = gsec5y;
        this.gsec10y = gsec10y;
    }

    public BigDecimal gsec3m()  { return gsec3m; }
    public BigDecimal gsec1y()  { return gsec1y; }
    public BigDecimal gsec5y()  { return gsec5y; }
    public BigDecimal gsec10y() { return gsec10y; }

    /** 10Y − 3M slope in percentage points, or null when either tenor is unconfigured. */
    public BigDecimal slope10y3m() {
        if (gsec3m == null || gsec10y == null) return null;
        return gsec10y.subtract(gsec3m);
    }

    /** 10Y − 1Y slope in percentage points, or null when either tenor is unconfigured. */
    public BigDecimal slope10y1y() {
        if (gsec1y == null || gsec10y == null) return null;
        return gsec10y.subtract(gsec1y);
    }

    public CurveShape shape() {
        BigDecimal slope = slope10y3m();
        if (slope == null) return CurveShape.UNKNOWN;
        double s = slope.doubleValue();
        if (s < 0) return CurveShape.INVERTED;
        if (s <= 0.5) return CurveShape.FLAT;
        if (s > 1.5) return CurveShape.STEEP;
        return CurveShape.NORMAL;
    }

    public enum CurveShape {
        INVERTED,  // slope < 0
        FLAT,      // 0 ≤ slope ≤ 50bp
        NORMAL,    // 50bp < slope ≤ 150bp
        STEEP,     // slope > 150bp
        UNKNOWN    // tenor data unavailable
    }
}
