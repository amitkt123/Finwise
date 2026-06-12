package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.LiquidityReport;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Liquidity-adjusted risk (Phase 9b).
 *
 * Spreads come from the Corwin-Schultz (2012) high/low estimator: for two consecutive
 * days it isolates the bid-ask bounce from the (overnight-adjusted) price range. Each
 * 2-day window's spread is floored at zero before being averaged over up to 60 days.
 *
 * Days-to-liquidate uses a 20%-of-ADV20 participation cap. The liquidity add-on to
 * VaR95 is a half-spread liquidation cost, {@code 0.5 Σ S_i w_i V}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiquidityService {

    private final InvestmentRepository investmentRepository;
    private final StockPriceHistoryRepository priceRepo;

    /** Participation cap: at most 20% of ADV20 can be traded per day without impact. */
    static final double PARTICIPATION_CAP = 0.20;
    static final int ADV_WINDOW = 20;
    static final int SPREAD_WINDOW = 60;
    static final double ILLIQUID_DTL = 5.0;
    private static final double DENOM = 3.0 - 2.0 * Math.sqrt(2.0); // 3 − 2√2

    /**
     * Computes the liquidity report for the user's equity portfolio.
     *
     * @param var95 parametric VaR95 (₹) from the risk decomposition — the LVaR base
     */
    public Optional<LiquidityReport> compute(String userId, double var95) {
        List<Investment> equities = investmentRepository.findActiveInvestments(userId).stream()
                .filter(inv -> inv.getSymbol() != null && !inv.getSymbol().isBlank())
                .filter(inv -> inv.getCurrentValue() != null
                        && inv.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (equities.isEmpty()) return Optional.empty();

        double totalValue = equities.stream()
                .mapToDouble(inv -> inv.getCurrentValue().doubleValue()).sum();
        if (totalValue <= 0) return Optional.empty();

        LocalDate since = LocalDate.now().minusDays(180);

        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Double> spreadBps = new LinkedHashMap<>();
        Map<String, Double> dtl = new LinkedHashMap<>();
        Map<String, Double> weights = new LinkedHashMap<>();
        List<String> illiquid = new ArrayList<>();

        double liquidityCost = 0.0;       // 0.5 Σ S_i w_i V
        double w1d = 0.0, w5d = 0.0;

        for (Investment inv : equities) {
            String sym = inv.getSymbol().toUpperCase();
            double weight = inv.getCurrentValue().doubleValue() / totalValue;
            weights.put(sym, weight);

            List<StockPriceHistory> history = priceRepo.findRecentBySymbol(sym, since); // DESC
            if (history.size() < ADV_WINDOW + 1) {
                excluded.add(sym);
                notes.add("LIQ_EXCLUDED: " + sym + " — insufficient price history for spread/ADV");
                continue;
            }

            // Ascending for the spread series; DESC head for ADV20.
            List<StockPriceHistory> asc = history.stream()
                    .sorted(Comparator.comparing(StockPriceHistory::getPriceDate)).toList();

            double[] highs = asc.stream().mapToDouble(h -> dbl(h.getHighPrice())).toArray();
            double[] lows  = asc.stream().mapToDouble(h -> dbl(h.getLowPrice())).toArray();
            if (!validHighLow(highs, lows)) {
                excluded.add(sym);
                notes.add("LIQ_EXCLUDED: " + sym + " — missing or non-positive high/low prices");
                continue;
            }

            double spread = corwinSchultzSpread(highs, lows);   // fraction
            double adv20 = avgVolume(asc, ADV_WINDOW);
            double shares = inv.getQuantity() != null ? inv.getQuantity().doubleValue() : 0.0;
            double daysToLiquidate = (adv20 > 0)
                    ? shares / (PARTICIPATION_CAP * adv20) : Double.POSITIVE_INFINITY;

            included.add(sym);
            spreadBps.put(sym, spread * 10_000.0);
            dtl.put(sym, daysToLiquidate);
            if (daysToLiquidate > ILLIQUID_DTL) illiquid.add(sym);
            if (daysToLiquidate <= 1.0) w1d += weight;
            if (daysToLiquidate <= 5.0) w5d += weight;

            liquidityCost += 0.5 * spread * weight * totalValue;
        }

        if (included.isEmpty()) return Optional.empty();

        double lvar95 = var95 + liquidityCost;
        double lvar95Stressed = var95 + 2.0 * liquidityCost; // 2× spread crisis proxy

        String headline = String.format(
                "Liquidity add-on ₹%.0f to VaR95 (%.0f%% liquidatable in 1d, %.0f%% in 5d%s)",
                liquidityCost, w1d * 100, w5d * 100,
                illiquid.isEmpty() ? "" : "; illiquid: " + String.join(", ", illiquid));

        log.info("[Liquidity] included={}, cost=₹{}, LVaR95=₹{}, illiquid={}",
                included.size(), String.format("%.0f", liquidityCost),
                String.format("%.0f", lvar95), illiquid);

        return Optional.of(new LiquidityReport(
                included, excluded, Collections.unmodifiableList(notes),
                spreadBps, dtl, w1d, w5d,
                lvar95, lvar95Stressed, liquidityCost,
                illiquid, headline));
    }

    // ── Corwin-Schultz estimator ─────────────────────────────────────────────────

    /**
     * Average Corwin-Schultz spread (fraction) over the most recent {@code SPREAD_WINDOW}
     * 2-day windows. Negative per-window estimates are floored at zero before averaging.
     *
     * @param highs daily highs (ascending date)
     * @param lows  daily lows  (ascending date)
     */
    static double corwinSchultzSpread(double[] highs, double[] lows) {
        int n = highs.length;
        if (n < 2) return 0.0;
        int windows = n - 1;
        int start = Math.max(0, windows - SPREAD_WINDOW);   // keep only the last SPREAD_WINDOW windows
        double sum = 0.0;
        int count = 0;
        for (int t = start; t < windows; t++) {
            double s = singleWindowSpread(highs[t], lows[t], highs[t + 1], lows[t + 1]);
            sum += Math.max(0.0, s);                          // floor negatives at 0
            count++;
        }
        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Corwin-Schultz spread (fraction) for a single 2-day window, with the overnight-gap
     * adjustment: day t+1's high/low are shifted so its range abuts day t's, removing the
     * close-to-open jump from the 2-day range γ.
     */
    static double singleWindowSpread(double h0, double l0, double h1, double l1) {
        if (l1 > h0) {                 // gap up overnight → shift day 1 down
            double gap = l1 - h0;
            h1 -= gap; l1 -= gap;
        } else if (h1 < l0) {          // gap down overnight → shift day 1 up
            double gap = l0 - h1;
            h1 += gap; l1 += gap;
        }

        double beta = sq(Math.log(h0 / l0)) + sq(Math.log(h1 / l1));
        double hMax = Math.max(h0, h1);
        double lMin = Math.min(l0, l1);
        double gamma = sq(Math.log(hMax / lMin));

        double alpha = (Math.sqrt(2.0 * beta) - Math.sqrt(beta)) / DENOM
                - Math.sqrt(gamma / DENOM);
        return 2.0 * (Math.exp(alpha) - 1.0) / (1.0 + Math.exp(alpha));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static double avgVolume(List<StockPriceHistory> ascending, int window) {
        int n = ascending.size();
        int from = Math.max(0, n - window);
        long sum = 0;
        int count = 0;
        for (int i = from; i < n; i++) {
            Long v = ascending.get(i).getVolume();
            if (v != null && v > 0) { sum += v; count++; }
        }
        return count > 0 ? (double) sum / count : 0.0;
    }

    private static boolean validHighLow(double[] highs, double[] lows) {
        for (int i = 0; i < highs.length; i++) {
            if (!(highs[i] > 0) || !(lows[i] > 0) || highs[i] < lows[i]) return false;
        }
        return true;
    }

    private static double dbl(BigDecimal b) {
        return b != null ? b.doubleValue() : Double.NaN;
    }

    private static double sq(double x) {
        return x * x;
    }
}
