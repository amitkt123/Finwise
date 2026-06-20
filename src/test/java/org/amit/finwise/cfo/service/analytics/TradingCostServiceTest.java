package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.service.analytics.TradingCostService.CostEstimate;
import org.amit.finwise.cfo.service.analytics.TradingCostService.Side;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase F: the cost estimate must reproduce the published NSE delivery-equity schedule for a
 * worked example, charge stamp duty on buys only, and add half the spread as impact.
 */
class TradingCostServiceTest {

    private static final double EPS = 1e-6;
    private final TradingCostService service = new TradingCostService();

    @Test
    void sellLegMatchesPublishedSchedule() {
        // ₹10,00,000 sell, no spread → impact 0.
        CostEstimate c = service.estimate(Side.SELL, 1_000_000, 0.0);

        assertEquals(0.0, c.brokerage(), EPS, "discount-broker delivery brokerage is free");
        assertEquals(1_000.0, c.stt(), EPS, "STT 0.1% on the sell leg");
        assertEquals(29.7, c.exchangeTxn(), EPS, "NSE 0.00297% transaction charge");
        assertEquals(1.0, c.sebiFee(), EPS, "SEBI ₹10/crore");
        assertEquals(0.18 * (29.7 + 1.0), c.gst(), EPS, "18% GST on exchange + SEBI");
        assertEquals(0.0, c.stampDuty(), EPS, "no stamp duty on a sell");
        assertEquals(0.0, c.impact(), EPS);

        double expectedTotal = 1_000.0 + 29.7 + 1.0 + 0.18 * (29.7 + 1.0);
        assertEquals(expectedTotal, c.total(), EPS);
    }

    @Test
    void buyLegAddsStampDuty() {
        CostEstimate buy = service.estimate(Side.BUY, 1_000_000, 0.0);
        CostEstimate sell = service.estimate(Side.SELL, 1_000_000, 0.0);

        assertEquals(150.0, buy.stampDuty(), EPS, "stamp duty 0.015% on the buy leg");
        assertEquals(sell.total() + 150.0, buy.total(), EPS, "buy = sell + stamp duty");
    }

    @Test
    void impactIsHalfTheSpread() {
        // 20 bps spread on ₹10,00,000 → half-spread impact = 0.5 × 0.002 × 1e6 = ₹1,000.
        CostEstimate withSpread = service.estimate(Side.SELL, 1_000_000, 0.002);
        CostEstimate noSpread = service.estimate(Side.SELL, 1_000_000, 0.0);

        assertEquals(1_000.0, withSpread.impact(), EPS);
        assertEquals(noSpread.total() + 1_000.0, withSpread.total(), EPS);
    }

    @Test
    void totalBpsAndAbsoluteNotional() {
        CostEstimate c = service.estimate(Side.SELL, -500_000, 0.0); // sign-agnostic
        assertEquals(500_000, c.notional(), EPS);
        assertEquals(c.total() / 500_000 * 10_000.0, c.totalBps(), EPS);
    }
}
