package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.service.StockPriceService;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Time-Weighted Rate of Return (TWRR) from daily portfolio snapshots and
 * external cash flows (BUY = money in, SELL = money out).
 *
 * TWRR isolates investment performance from the timing/size of contributions —
 * the same measure SEBI mandates for PMS reporting. Simple cost-basis P&L is
 * misleading under ongoing SIPs.
 *
 * Sub-period return between consecutive snapshots, with flows assumed to occur
 * at the start of the sub-period:
 *
 *   r_t = V_t / (V_{t-1} + CF_t) − 1
 *
 * TWRR = Π (1 + r_t) − 1, annualized geometrically over the covered days.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioPerformanceService {

    private final PortfolioSnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;
    private final StockPriceHistoryRepository priceHistoryRepository;

    private static final int LOOKBACK_DAYS = 730;
    private static final int MIN_SNAPSHOTS = 5;

    public Optional<TwrrResult> computeTwrr(String userId) {
        LocalDateTime since = LocalDateTime.now().minusDays(LOOKBACK_DAYS);
        List<PortfolioSnapshot> snapshots = snapshotRepository.findRecentSnapshots(userId, since);
        if (snapshots.size() < MIN_SNAPSHOTS) {
            log.debug("[TWRR] Only {} snapshots for {} — need {}", snapshots.size(), userId, MIN_SNAPSHOTS);
            return Optional.empty();
        }

        // Keep the last snapshot per calendar day, ascending by date
        NavigableMap<LocalDate, Double> dailyValue = new TreeMap<>();
        for (PortfolioSnapshot s : snapshots) { // repository returns DESC; first seen per day wins
            if (s.getCurrentValue() == null) continue;
            dailyValue.putIfAbsent(s.getSnapshotTime().toLocalDate(), s.getCurrentValue().doubleValue());
        }
        if (dailyValue.size() < MIN_SNAPSHOTS) return Optional.empty();

        // Net external flow per date: BUY adds capital, SELL removes it
        Map<LocalDate, Double> netFlow = new HashMap<>();
        for (Transaction t : transactionRepository.findBuySellTransactionsAsc(userId)) {
            if (t.getAmount() == null || t.getTransactionDate() == null) continue;
            double amt = t.getAmount().doubleValue();
            netFlow.merge(t.getTransactionDate(),
                    t.getTransactionType() == Transaction.TransactionType.BUY ? amt : -amt,
                    Double::sum);
        }

        List<LocalDate> dates = new ArrayList<>(dailyValue.keySet());
        NavigableMap<LocalDate, Double> benchmark = benchmarkCloses(dates.getFirst());

        double linked = 1.0;
        int subPeriods = 0;
        // Matched sub-periods where both portfolio and benchmark returns exist
        double benchLinked = 1.0;
        double portfolioLinkedMatched = 1.0;
        List<Double> activeReturns = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            LocalDate prev = dates.get(i - 1);
            LocalDate curr = dates.get(i);
            double vPrev = dailyValue.get(prev);
            double vCurr = dailyValue.get(curr);

            // Flows dated after the previous snapshot up to and including this one
            double flow = 0;
            for (LocalDate d = prev.plusDays(1); !d.isAfter(curr); d = d.plusDays(1)) {
                flow += netFlow.getOrDefault(d, 0.0);
            }

            double denominator = vPrev + flow;
            if (denominator <= 0) continue; // degenerate sub-period (full withdrawal etc.)
            double rp = vCurr / denominator - 1.0;
            linked *= 1.0 + rp;
            subPeriods++;

            // Same-window benchmark return from nearest at-or-before closes
            Map.Entry<LocalDate, Double> bPrev = benchmark.floorEntry(prev);
            Map.Entry<LocalDate, Double> bCurr = benchmark.floorEntry(curr);
            if (bPrev != null && bCurr != null && bPrev.getValue() > 0
                    && bCurr.getKey().isAfter(bPrev.getKey())) {
                double rb = bCurr.getValue() / bPrev.getValue() - 1.0;
                benchLinked *= 1.0 + rb;
                portfolioLinkedMatched *= 1.0 + rp;
                activeReturns.add(rp - rb);
            }
        }

        if (subPeriods == 0) return Optional.empty();

        double twrr = linked - 1.0;
        long coveredDays = ChronoUnit.DAYS.between(dates.getFirst(), dates.getLast());
        double annualized = coveredDays >= 30
                ? Math.pow(1.0 + twrr, 365.0 / coveredDays) - 1.0
                : Double.NaN; // too short to annualize meaningfully

        // Benchmark comparison only when most sub-periods could be matched —
        // an active return over a different window is not an active return.
        double benchmarkTwrr = Double.NaN;
        double annualizedBenchmark = Double.NaN;
        double activeReturnAnnualized = Double.NaN;
        double trackingError = Double.NaN;
        double informationRatio = Double.NaN;
        int matched = activeReturns.size();
        if (matched >= Math.max(2, subPeriods / 2) && coveredDays >= 30) {
            benchmarkTwrr = benchLinked - 1.0;
            annualizedBenchmark = Math.pow(1.0 + benchmarkTwrr, 365.0 / coveredDays) - 1.0;
            double annualizedMatched =
                    Math.pow(portfolioLinkedMatched, 365.0 / coveredDays) - 1.0;
            activeReturnAnnualized = annualizedMatched - annualizedBenchmark;
            if (matched > 2) {
                // Annualize the sub-period active-return stdev by the actual
                // observation frequency (sub-periods are not always daily)
                double[] a = activeReturns.stream().mapToDouble(Double::doubleValue).toArray();
                double periodsPerYear = matched * 365.0 / coveredDays;
                trackingError = Math.sqrt(StatUtils.variance(a)) * Math.sqrt(periodsPerYear);
                informationRatio = trackingError > 0
                        ? activeReturnAnnualized / trackingError : Double.NaN;
            }
        }

        return Optional.of(new TwrrResult(
                twrr, annualized, dates.getFirst(), dates.getLast(), subPeriods,
                BigDecimal.valueOf(dailyValue.get(dates.getLast())),
                benchmarkTwrr, annualizedBenchmark,
                activeReturnAnnualized, trackingError, informationRatio));
    }

    /** Nifty 50 adjusted closes from the DB, ascending — empty map when absent. */
    private NavigableMap<LocalDate, Double> benchmarkCloses(LocalDate from) {
        NavigableMap<LocalDate, Double> closes = new TreeMap<>();
        for (StockPriceHistory row : priceHistoryRepository.findRecentBySymbol(
                StockPriceService.NIFTY_SYMBOL, from.minusDays(7))) {
            BigDecimal px = row.getAdjustedClose() != null ? row.getAdjustedClose() : row.getClosePrice();
            if (row.getPriceDate() != null && px != null && px.signum() > 0) {
                closes.put(row.getPriceDate(), px.doubleValue());
            }
        }
        return closes;
    }

    public record TwrrResult(
            double twrr,                 // cumulative over the covered period
            double annualizedTwrr,       // NaN when period < 30 days
            LocalDate from,
            LocalDate to,
            int subPeriods,
            BigDecimal latestValue,
            double benchmarkTwrr,        // Nifty 50 over the matched sub-periods; NaN if unavailable
            double annualizedBenchmarkTwrr,
            double activeReturnAnnualized,   // portfolio − benchmark, annualized
            double trackingErrorAnnualized,  // stdev of sub-period active returns, annualized
            double informationRatio          // active return / tracking error
    ) {}
}
