package org.amit.finwise.cfo.service.analytics;

import org.springframework.stereotype.Service;

/**
 * Transaction-cost estimator for cash-equity (delivery) trades (Honest-Insights Phase F).
 *
 * <p>Turns a notional trade into the all-in round-trip cost so a "trim ₹X" recommendation can
 * be stated <b>net of costs</b>, and so trims whose risk benefit is smaller than their cost can
 * be suppressed. Every rate is a published statutory/exchange figure for NSE delivery equity,
 * held as a constant so a unit test can reproduce the schedule byte-for-byte:
 *
 * <ul>
 *   <li><b>STT</b> — 0.1% on both buy and sell legs (delivery).</li>
 *   <li><b>Exchange transaction charge</b> — NSE 0.00297% of turnover.</li>
 *   <li><b>SEBI turnover fee</b> — ₹10 per crore = 0.0001%.</li>
 *   <li><b>Stamp duty</b> — 0.015%, buy side only (delivery).</li>
 *   <li><b>GST</b> — 18% on (brokerage + exchange + SEBI).</li>
 *   <li><b>Brokerage</b> — discount-broker delivery is ₹0, capped at ₹20 otherwise.</li>
 *   <li><b>Impact</b> — half the bid-ask spread crossed on execution
 *       ({@code 0.5 × spread × notional}), the same convention as
 *       {@link LiquidityService}'s liquidity add-on. Spread is sourced from the
 *       Corwin-Schultz estimate in {@link org.amit.finwise.cfo.model.LiquidityReport}.</li>
 * </ul>
 */
@Service
public class TradingCostService {

    public enum Side { BUY, SELL }

    /** Securities Transaction Tax on delivery equity — 0.1% on both legs. */
    static final double STT_DELIVERY = 0.001;
    /** NSE exchange transaction charge — 0.00297% of turnover. */
    static final double EXCHANGE_TXN_NSE = 0.0000297;
    /** SEBI turnover fee — ₹10 per crore = 0.0001%. */
    static final double SEBI_TURNOVER = 0.000001;
    /** Stamp duty — 0.015%, buy side only (delivery). */
    static final double STAMP_DUTY_BUY = 0.00015;
    /** GST — 18% on brokerage + exchange + SEBI charges. */
    static final double GST = 0.18;
    /** Discount-broker delivery brokerage rate (0 = free). */
    static final double BROKERAGE_RATE = 0.0;
    /** Per-order brokerage cap (₹20) — applies only when {@link #BROKERAGE_RATE} > 0. */
    static final double BROKERAGE_CAP = 20.0;

    /**
     * Estimate the all-in cost of trading {@code notional} rupees of one leg.
     *
     * @param side           BUY adds stamp duty; SELL does not
     * @param notional       trade value in ₹ (absolute value is used)
     * @param spreadFraction bid-ask spread as a fraction (e.g. 0.002 = 20 bps); 0 when unknown,
     *                        in which case impact is zero rather than fabricated
     */
    public CostEstimate estimate(Side side, double notional, double spreadFraction) {
        double v = Math.abs(notional);
        double brokerage = Math.min(BROKERAGE_CAP, BROKERAGE_RATE * v);
        double stt = STT_DELIVERY * v;
        double exchangeTxn = EXCHANGE_TXN_NSE * v;
        double sebiFee = SEBI_TURNOVER * v;
        double gst = GST * (brokerage + exchangeTxn + sebiFee);
        double stampDuty = side == Side.BUY ? STAMP_DUTY_BUY * v : 0.0;
        double impact = 0.5 * Math.max(0.0, spreadFraction) * v;
        double total = brokerage + stt + exchangeTxn + sebiFee + gst + stampDuty + impact;
        return new CostEstimate(v, brokerage, stt, exchangeTxn, sebiFee, gst, stampDuty, impact, total);
    }

    /** Itemized cost of one trade leg, every figure in ₹. */
    public record CostEstimate(
            double notional,
            double brokerage,
            double stt,
            double exchangeTxn,
            double sebiFee,
            double gst,
            double stampDuty,
            double impact,
            double total
    ) {
        /** Total cost as a fraction of notional, in basis points. */
        public double totalBps() {
            return notional > 0 ? total / notional * 10_000.0 : 0.0;
        }

        /** Human-readable breakdown for an {@code InsightCard} computation method line. */
        public String breakdown() {
            StringBuilder sb = new StringBuilder();
            if (brokerage > 0) sb.append(String.format("brokerage ₹%,.0f + ", brokerage));
            sb.append(String.format("STT ₹%,.0f + exch/SEBI/GST ₹%,.0f + ½-spread impact ₹%,.0f",
                    stt, exchangeTxn + sebiFee + gst, impact));
            if (stampDuty > 0) sb.append(String.format(" + stamp ₹%,.0f", stampDuty));
            return sb.toString();
        }
    }
}
