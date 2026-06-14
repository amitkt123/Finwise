package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.QuarterlyFundamentals;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.repository.QuarterlyFundamentalsRepository;
import org.amit.finwise.cfo.repository.StockFundamentalsRepository;
import org.amit.finwise.cfo.service.price.PriceDataProvider;
import org.amit.finwise.cfo.service.price.YahooFinancePriceProvider;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches, computes, and persists stock fundamental metrics.
 *
 * Metrics sourced from Yahoo quoteSummary:
 *   - Valuation: P/E, P/B, EV/EBITDA, EV/Sales, PEG
 *   - Profitability: margins (gross, EBITDA, net), ROE, D/E
 *   - Growth: revenue growth, FCF
 *
 * Valuation z-scores:
 *   z = (current - mean) / stdev → label CHEAP (<-1), FAIR ([-1, 1]), EXPENSIVE (>1)
 *   Computed against 2-year history; if insufficient data, flag DATA_INCOMPLETE.
 *
 * Quality gate: null margin or earnings → set dataQualityNotes flag.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsService {

    private final StockFundamentalsRepository fundamentalsRepo;
    private final YahooFinancePriceProvider yahooProvider;
    private final QuarterlyFundamentalsRepository quarterlyRepo;
    private final FundamentalTrendService fundamentalTrendService;

    private static final int VALUATION_HISTORY_DAYS = 730; // 2 years
    private static final int MIN_HISTORY_FOR_ZSCORE = 60;

    /**
     * Fetch and persist fundamentals for a symbol.
     * Returns the persisted StockFundamentals; missing data is captured in dataQualityNotes.
     */
    public StockFundamentals fetchAndPersist(String symbol) {
        try {
            YahooFinancePriceProvider.FundamentalsSnapshot snapshot = yahooProvider.fetchFundamentals(symbol);
            return computeAndPersist(symbol, snapshot);
        } catch (PriceDataProvider.PriceProviderException e) {
            log.warn("[Fundamentals] Failed to fetch data for {}: {}", symbol, e.getMessage());
            // Return a stub with data quality flag
            return persistStubWithError(symbol, "FETCH_ERROR: " + e.getMessage());
        }
    }

    private StockFundamentals computeAndPersist(String symbol,
                                                 YahooFinancePriceProvider.FundamentalsSnapshot snap) {
        List<String> dataGaps = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // Phase 7: refresh quarterly statement history first so the trend
        // checks below see the newest quarters. Non-fatal — the point-in-time
        // snapshot still persists if the quarterly endpoint fails.
        try {
            refreshQuarterlyHistory(symbol);
        } catch (Exception e) {
            log.warn("[Fundamentals] Quarterly history refresh failed for {}: {}", symbol, e.getMessage());
            dataGaps.add("INCOMPLETE:QUARTERLY_HISTORY");
        }

        // ── Data quality checks ──────────────────────────────────────────────
        if (snap.getNetProfitMargin() == null) {
            dataGaps.add("INCOMPLETE:NET_MARGIN");
        }
        if (snap.getGrossMargin() == null) {
            dataGaps.add("INCOMPLETE:GROSS_MARGIN");
        }
        if (snap.getTrailingPE() == null) {
            dataGaps.add("INCOMPLETE:EARNINGS");
        }

        // ── Compute valuation z-scores ───────────────────────────────────────
        BigDecimal peZScore = null;
        BigDecimal evEbitdaZScore = null;
        String valuationLabel = null;

        if (snap.getTrailingPE() != null && snap.getTrailingPE().compareTo(BigDecimal.ZERO) > 0) {
            peZScore = computePEZScore(symbol, snap.getTrailingPE());
        }
        if (snap.getEvToEbitda() != null && snap.getEvToEbitda().compareTo(BigDecimal.ZERO) > 0) {
            evEbitdaZScore = computeEVEbitdaZScore(symbol, snap.getEvToEbitda());
        }

        // Label valuation: prefer P/E if available, else EV/EBITDA
        if (peZScore != null) {
            valuationLabel = labelFromZScore(peZScore);
        } else if (evEbitdaZScore != null) {
            valuationLabel = labelFromZScore(evEbitdaZScore);
        }

        // Profitability flag
        boolean isProfitable = snap.getNetProfitMargin() != null
                && snap.getNetProfitMargin().compareTo(BigDecimal.ZERO) > 0;

        // ── Modified Piotroski F-Score ───────────────────────────────────────
        FScore fScore = computeModifiedPiotroski(symbol, snap, isProfitable);
        if (fScore == null) {
            dataGaps.add("INCOMPLETE:PIOTROSKI");
        }

        // Build the entity
        StockFundamentals fundamentals = StockFundamentals.builder()
                .symbol(symbol.toUpperCase())
                .snapshotDate(today)
                .trailingPE(snap.getTrailingPE())
                .forwardPE(snap.getForwardPE())
                .priceToBook(snap.getPriceToBook())
                .evToEbitda(snap.getEvToEbitda())
                .evToSales(snap.getEvToRevenue())
                .pegRatio(snap.getPegRatio())
                .dividendYield(snap.getDividendYield())
                .revenueGrowth(snap.getRevenueGrowth())
                .grossMargin(snap.getGrossMargin())
                .ebitdaMargin(snap.getEbitdaMargin())
                .operatingMargin(snap.getOperatingMargin())
                .netProfitMargin(snap.getNetProfitMargin())
                .roe(snap.getReturnOnEquity())
                .debtToEquity(snap.getDebtToEquity())
                .freeCashFlow(snap.getFreeCashFlow())
                .isProfitable(isProfitable)
                .peZScore(peZScore)
                .evEbitdaZScore(evEbitdaZScore)
                .valuationLabel(valuationLabel)
                .piotroskiFScore(fScore != null ? fScore.passed : null)
                .piotroskiMaxChecks(fScore != null ? fScore.checked : null)
                .piotroskiDetail(fScore != null ? fScore.detail : null)
                .dataQualityNotes(dataGaps.isEmpty() ? null : String.join("; ", dataGaps))
                .build();

        return fundamentalsRepo.save(fundamentals);
    }

    /**
     * Modified Piotroski F-Score from available fundamentals.
     *
     * The classic 9-point F-Score needs balance-sheet and cash-flow statement
     * history (current ratio, share count, accruals, asset turnover) that the
     * free Yahoo snapshot doesn't carry. This version scores the 6 checks that
     * ARE computable — three point-in-time, three year-over-year against the
     * snapshot taken ~1 year ago:
     *
     *   1. PROFITABLE  — net profit margin > 0          (proxy for ROA > 0)
     *   2. FCF         — free cash flow > 0             (proxy for CFO > 0)
     *   3. REVGROWTH   — revenue growth > 0             (proxy for Δ asset turnover)
     *   4. ROE_TREND   — ROE up YoY                     (proxy for Δ ROA)
     *   5. LEVERAGE    — debt/equity flat or down YoY
     *   6. GMARGIN     — gross margin up YoY
     *
     * Phase 7 extends with two classic checks from quarterly statement history:
     *   7. ACCRUALS    — operating cash flow exceeds net income (Sloan ratio < 0)
     *   8. NO_DILUTION — shares outstanding flat or down YoY
     *
     * Returns null when fewer than 3 checks are computable.
     */
    private FScore computeModifiedPiotroski(String symbol,
                                            YahooFinancePriceProvider.FundamentalsSnapshot snap,
                                            boolean isProfitable) {
        int passed = 0, checked = 0;
        StringBuilder detail = new StringBuilder();

        if (snap.getNetProfitMargin() != null) {
            checked++;
            if (isProfitable) passed++;
            detail.append("PROFITABLE").append(isProfitable ? "+" : "-").append(' ');
        }
        if (snap.getFreeCashFlow() != null) {
            checked++;
            boolean pass = snap.getFreeCashFlow().compareTo(BigDecimal.ZERO) > 0;
            if (pass) passed++;
            detail.append("FCF").append(pass ? "+" : "-").append(' ');
        }
        if (snap.getRevenueGrowth() != null) {
            checked++;
            boolean pass = snap.getRevenueGrowth().compareTo(BigDecimal.ZERO) > 0;
            if (pass) passed++;
            detail.append("REVGROWTH").append(pass ? "+" : "-").append(' ');
        }

        // Year-ago snapshot: closest record 10–14 months back
        StockFundamentals yearAgo = fundamentalsRepo
                .findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(
                        symbol.toUpperCase(),
                        LocalDate.now().minusMonths(14),
                        LocalDate.now().minusMonths(10))
                .stream().reduce((_, second) -> second) // latest in window
                .orElse(null);

        if (yearAgo != null) {
            if (snap.getReturnOnEquity() != null && yearAgo.getRoe() != null) {
                checked++;
                boolean pass = snap.getReturnOnEquity().compareTo(yearAgo.getRoe()) > 0;
                if (pass) passed++;
                detail.append("ROE_TREND").append(pass ? "+" : "-").append(' ');
            }
            if (snap.getDebtToEquity() != null && yearAgo.getDebtToEquity() != null) {
                checked++;
                boolean pass = snap.getDebtToEquity().compareTo(yearAgo.getDebtToEquity()) <= 0;
                if (pass) passed++;
                detail.append("LEVERAGE").append(pass ? "+" : "-").append(' ');
            }
            if (snap.getGrossMargin() != null && yearAgo.getGrossMargin() != null) {
                checked++;
                boolean pass = snap.getGrossMargin().compareTo(yearAgo.getGrossMargin()) > 0;
                if (pass) passed++;
                detail.append("GMARGIN").append(pass ? "+" : "-").append(' ');
            }
        }

        // Phase 7: accrual and dilution checks from quarterly statement history
        try {
            Optional<FundamentalTrendService.TrendAnalysis> trendOpt =
                    fundamentalTrendService.analyzeTrend(symbol.toUpperCase(), LocalDate.now());
            if (trendOpt.isPresent()) {
                FundamentalTrendService.TrendAnalysis trend = trendOpt.get();
                if (!Double.isNaN(trend.sloanAccrualRatio())) {
                    checked++;
                    boolean pass = trend.sloanAccrualRatio() < 0; // cash flow exceeds reported earnings
                    if (pass) passed++;
                    detail.append("ACCRUALS").append(pass ? "+" : "-").append(' ');
                }
                if (trend.dilutionComputable()) {
                    checked++;
                    boolean pass = !trend.shareDilution();
                    if (pass) passed++;
                    detail.append("NO_DILUTION").append(pass ? "+" : "-").append(' ');
                }
            }
        } catch (Exception e) {
            log.debug("[Fundamentals] Trend-based Piotroski checks skipped for {}: {}", symbol, e.getMessage());
        }

        if (checked < 3) {
            log.debug("[Fundamentals] Piotroski skipped for {} — only {} checks computable", symbol, checked);
            return null;
        }
        return new FScore(passed, checked, detail.toString().trim());
    }

    private record FScore(int passed, int checked, String detail) {}

    /**
     * Upsert Yahoo's trailing quarterly statements (~4 quarters per fetch) into
     * QuarterlyFundamentals. Existing (symbol, quarterEnd) rows are kept — the
     * table accumulates 8+ quarters across refreshes for trend analysis.
     */
    private void refreshQuarterlyHistory(String symbol) throws PriceDataProvider.PriceProviderException {
        String sym = symbol.toUpperCase();
        List<YahooFinancePriceProvider.QuarterlySnapshot> fetched =
                yahooProvider.fetchQuarterlyFundamentals(symbol);
        int saved = 0;
        for (YahooFinancePriceProvider.QuarterlySnapshot q : fetched) {
            if (q.quarterEnd == null || quarterlyRepo.existsBySymbolAndQuarterEnd(sym, q.quarterEnd)) {
                continue;
            }
            BigDecimal grossMargin = null;
            if (q.grossProfit != null && q.revenue != null && q.revenue.signum() > 0) {
                grossMargin = q.grossProfit.multiply(new BigDecimal("100"))
                        .divide(q.revenue, 2, RoundingMode.HALF_UP);
            }
            quarterlyRepo.save(QuarterlyFundamentals.builder()
                    .symbol(sym)
                    .quarterEnd(q.quarterEnd)
                    .revenue(q.revenue)
                    .netIncome(q.netIncome)
                    .eps(q.eps)
                    .totalAssets(q.totalAssets)
                    .totalLiabilities(q.totalLiabilities)
                    .operatingCashFlow(q.operatingCashFlow)
                    .grossMargin(grossMargin)
                    .build());
            saved++;
        }
        if (saved > 0) {
            log.info("[Fundamentals] {} — persisted {} new quarterly records", sym, saved);
        }
    }

    private StockFundamentals persistStubWithError(String symbol, String errorNote) {
        StockFundamentals stub = StockFundamentals.builder()
                .symbol(symbol.toUpperCase())
                .snapshotDate(LocalDate.now())
                .dataQualityNotes(errorNote)
                .build();
        return fundamentalsRepo.save(stub);
    }

    /**
     * Compute z-score of current P/E against 2-year history.
     * If insufficient history (< 60 obs), return null and data gap will be set elsewhere.
     */
    private BigDecimal computePEZScore(String symbol, BigDecimal currentPE) {
        LocalDate twoYearsAgo = LocalDate.now().minusDays(VALUATION_HISTORY_DAYS);
        List<StockFundamentals> history = fundamentalsRepo
                .findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(symbol, twoYearsAgo, LocalDate.now());

        // Filter nulls
        List<BigDecimal> peValues = history.stream()
                .map(StockFundamentals::getTrailingPE)
                .filter(pe -> pe != null && pe.compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (peValues.size() < MIN_HISTORY_FOR_ZSCORE) {
            log.debug("[Fundamentals] Insufficient P/E history for {} (n={})", symbol, peValues.size());
            return null;
        }

        return zScoreAgainstHistory(currentPE, peValues);
    }

    /**
     * Compute z-score of current EV/EBITDA against 2-year history.
     */
    private BigDecimal computeEVEbitdaZScore(String symbol, BigDecimal currentEVEbitda) {
        LocalDate twoYearsAgo = LocalDate.now().minusDays(VALUATION_HISTORY_DAYS);
        List<StockFundamentals> history = fundamentalsRepo
                .findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(symbol, twoYearsAgo, LocalDate.now());

        List<BigDecimal> evValues = history.stream()
                .map(StockFundamentals::getEvToEbitda)
                .filter(ev -> ev != null && ev.compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (evValues.size() < MIN_HISTORY_FOR_ZSCORE) {
            return null;
        }

        return zScoreAgainstHistory(currentEVEbitda, evValues);
    }

    /**
     * Z-score of {@code current} against a history of values using the unbiased
     * N−1 sample variance (consistent with CovarianceEngine's estimator choice).
     */
    private BigDecimal zScoreAgainstHistory(BigDecimal current, List<BigDecimal> history) {
        double[] values = history.stream().mapToDouble(BigDecimal::doubleValue).toArray();
        double mean = StatUtils.mean(values);
        double stdev = Math.sqrt(StatUtils.variance(values)); // N-1 denominator

        if (stdev < 0.01) {
            return BigDecimal.ZERO; // No variance
        }

        double zScore = (current.doubleValue() - mean) / stdev;
        return BigDecimal.valueOf(zScore).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Label valuation based on z-score: CHEAP (<-1), FAIR ([-1, 1]), EXPENSIVE (>1).
     */
    private String labelFromZScore(BigDecimal zScore) {
        if (zScore == null) return null;
        double z = zScore.doubleValue();
        if (z < -1.0) return "CHEAP";
        if (z > 1.0) return "EXPENSIVE";
        return "FAIR";
    }

    /**
     * Get the latest fundamentals for a symbol, or fetch if not cached today.
     */
    public StockFundamentals getLatest(String symbol) {
        Optional<StockFundamentals> cachedOpt = fundamentalsRepo
                .findTopBySymbolOrderBySnapshotDateDesc(symbol);

        if (cachedOpt.isPresent()) {
            StockFundamentals cached = cachedOpt.get();
            // Refresh daily
            if (cached.getSnapshotDate().equals(LocalDate.now())) {
                return cached;
            }
        }

        return fetchAndPersist(symbol);
    }
}
