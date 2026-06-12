package org.amit.finwise.cfo.model;

import java.util.List;
import java.util.Map;

/**
 * Liquidity-adjusted risk report (Phase 9b).
 *
 * Bid-ask spreads are estimated from daily high/low prices via the Corwin-Schultz
 * (2012) estimator — no quote data required. Negative window estimates are floored
 * at zero before averaging, per the paper's recommendation.
 *
 * Days-to-liquidate (DTL) assumes a participation cap of 20% of 20-day average daily
 * volume: {@code DTL_i = shares_i / (0.20 × ADV20_i)}. Names with {@code DTL > 5} are
 * flagged illiquid.
 *
 * The liquidity-adjusted VaR adds a half-spread liquidation cost to the parametric
 * VaR95: {@code LVaR = VaR95 + 0.5 × Σ S_i w_i V}. The stressed variant doubles the
 * spread (a crude crisis proxy).
 */
public record LiquidityReport(
        List<String> includedSymbols,
        List<String> excludedSymbols,
        List<String> dataQualityNotes,

        Map<String, Double> spreadBps,          // symbol → CS spread, basis points
        Map<String, Double> daysToLiquidate,    // symbol → DTL

        double weightLiquidatableIn1d,          // Σ w_i over names with DTL ≤ 1
        double weightLiquidatableIn5d,          // Σ w_i over names with DTL ≤ 5

        double lvar95,                          // VaR95 + 0.5 Σ S_i w_i V
        double lvar95Stressed,                  // VaR95 + Σ S_i w_i V (2× spread)
        double liquidityCost,                   // 0.5 Σ S_i w_i V (the LVaR add-on)

        List<String> illiquidSymbols,           // DTL > 5
        String headline
) {
}
