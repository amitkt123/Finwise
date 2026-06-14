package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.EvidenceItem;
import org.amit.finwise.cfo.model.QuarterlyFundamentals;
import org.amit.finwise.cfo.repository.QuarterlyFundamentalsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Analyzes persisted quarterly financial history (Phase 7) to compute:
 * - Revenue/EPS CAGR over the available span
 * - Net-margin trend (least-squares slope, percentage points per quarter)
 * - Sloan accrual ratio over the trailing 4 quarters (earnings-quality red flag > 0.10)
 * - Share dilution year-over-year
 *
 * Results feed the Piotroski F-Score (extending 6 → 8 classic checks) and the
 * scorecard growth pillar. Confidence is LOW below 6 quarters of history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalTrendService {

    private final QuarterlyFundamentalsRepository quarterlyRepo;

    public record TrendAnalysis(
            double revenueCagrPct,        // % annualized, NaN if not computable
            double epsCagrPct,            // % annualized, NaN if not computable
            double marginTrendSlope,      // net-margin percentage points per quarter, NaN if not computable
            double sloanAccrualRatio,     // (NI − CFO) / avg assets over trailing 4 quarters; NaN if not computable
            boolean shareDilution,        // true if shares outstanding grew YoY (only meaningful when computable)
            boolean dilutionComputable,   // share-count data exists across the YoY window
            QuarterlyFundamentals.TrendConfidence confidence,
            List<EvidenceItem> evidence
    ) {}

    /**
     * Analyze the trend for a symbol from all persisted quarterly history.
     * Returns empty when fewer than 2 quarters exist.
     */
    public Optional<TrendAnalysis> analyzeTrend(String symbol, LocalDate asOf) {
        List<QuarterlyFundamentals> quarters =
                new ArrayList<>(quarterlyRepo.findBySymbolOrderByQuarterEndDesc(symbol));
        if (quarters.size() < 2) {
            log.debug("[FundamentalTrend] {} — only {} quarters available", symbol, quarters.size());
            return Optional.empty();
        }
        quarters.sort(Comparator.comparing(QuarterlyFundamentals::getQuarterEnd)); // ascending

        List<EvidenceItem> evidence = new ArrayList<>();

        double revenueCagr = cagr(quarters, QuarterlyFundamentals::getRevenue);
        if (!Double.isNaN(revenueCagr)) {
            add(evidence, "GROWTH", "Revenue CAGR",
                    String.format("%.1f%%", revenueCagr), "Annualized over " + quarters.size() + " quarters",
                    revenueCagr > 10 ? "POSITIVE" : "BALANCED", 70, "quarterly_trends", asOf);
        }

        double epsCagr = cagr(quarters, QuarterlyFundamentals::getEps);
        if (!Double.isNaN(epsCagr)) {
            add(evidence, "GROWTH", "EPS CAGR",
                    String.format("%.1f%%", epsCagr), "Annualized over " + quarters.size() + " quarters",
                    epsCagr > 12 ? "POSITIVE" : "BALANCED", 70, "quarterly_trends", asOf);
        }

        double marginSlope = marginTrendSlope(quarters);
        double sloan = sloanAccrualRatio(quarters);

        boolean dilutionComputable = isDilutionComputable(quarters);
        boolean dilution = dilutionComputable && shareDilution(quarters);
        if (dilution) {
            add(evidence, "QUALITY", "Share dilution", "YoY increase",
                    "Shares outstanding grew over the last 4 quarters — drags per-share growth",
                    "NEGATIVE", 60, "quarterly_trends", asOf);
        }

        QuarterlyFundamentals.TrendConfidence conf = quarters.size() < 6
                ? QuarterlyFundamentals.TrendConfidence.LOW
                : quarters.size() < 9
                ? QuarterlyFundamentals.TrendConfidence.MEDIUM
                : QuarterlyFundamentals.TrendConfidence.HIGH;

        return Optional.of(new TrendAnalysis(
                revenueCagr, epsCagr, marginSlope, sloan, dilution, dilutionComputable, conf, evidence));
    }

    /**
     * CAGR annualized over the span between first and last quarter.
     * The span of n quarterly observations is (n − 1) quarters = (n − 1)/4 years.
     * NaN when either endpoint is missing or non-positive.
     */
    private double cagr(List<QuarterlyFundamentals> quarters,
                        Function<QuarterlyFundamentals, BigDecimal> getter) {
        BigDecimal first = getter.apply(quarters.getFirst());
        BigDecimal last = getter.apply(quarters.getLast());
        if (first == null || last == null
                || first.doubleValue() <= 0 || last.doubleValue() <= 0) return Double.NaN;

        double years = (quarters.size() - 1) / 4.0;
        return (Math.pow(last.doubleValue() / first.doubleValue(), 1.0 / years) - 1) * 100;
    }

    /**
     * Least-squares slope of net margin (NI/Revenue, %) against quarter index.
     * Units: percentage points per quarter. NaN with fewer than 3 computable margins.
     */
    private double marginTrendSlope(List<QuarterlyFundamentals> quarters) {
        List<Double> margins = new ArrayList<>();
        for (QuarterlyFundamentals q : quarters) {
            if (q.getNetIncome() != null && q.getRevenue() != null
                    && q.getRevenue().doubleValue() > 0) {
                margins.add(q.getNetIncome().doubleValue() / q.getRevenue().doubleValue() * 100);
            }
        }
        int n = margins.size();
        if (n < 3) return Double.NaN;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += margins.get(i);
            sumXY += i * margins.get(i);
            sumX2 += (double) i * i;
        }
        return (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
    }

    /**
     * Sloan accrual ratio over the trailing window:
     *   (Σ NI − Σ CFO) / average total assets,
     * summing up to the last 4 quarters that have both NI and CFO, with assets
     * averaged between the window's endpoints. > 0.10 flags earnings whose
     * accrual component (not backed by cash) is historically mean-reverting.
     * NaN when no quarter has the full triple.
     */
    private double sloanAccrualRatio(List<QuarterlyFundamentals> quarters) {
        double sumAccruals = 0;
        Double firstAssets = null, lastAssets = null;
        int counted = 0;
        for (int i = quarters.size() - 1; i >= 0 && counted < 4; i--) {
            QuarterlyFundamentals q = quarters.get(i);
            if (q.getNetIncome() == null || q.getOperatingCashFlow() == null
                    || q.getTotalAssets() == null || q.getTotalAssets().doubleValue() <= 0) {
                continue;
            }
            sumAccruals += q.getNetIncome().doubleValue() - q.getOperatingCashFlow().doubleValue();
            if (lastAssets == null) lastAssets = q.getTotalAssets().doubleValue();
            firstAssets = q.getTotalAssets().doubleValue();
            counted++;
        }
        if (counted == 0) return Double.NaN;
        double avgAssets = (firstAssets + lastAssets) / 2.0;
        return sumAccruals / avgAssets;
    }

    /** Share-count data must exist at both ends of the YoY window. */
    private boolean isDilutionComputable(List<QuarterlyFundamentals> quarters) {
        if (quarters.size() < 5) return false;
        return quarters.getLast().getSharesOutstanding() != null
                && quarters.get(quarters.size() - 5).getSharesOutstanding() != null;
    }

    /** Latest share count vs 4 quarters earlier. */
    private boolean shareDilution(List<QuarterlyFundamentals> quarters) {
        BigDecimal recent = quarters.getLast().getSharesOutstanding();
        BigDecimal yearAgo = quarters.get(quarters.size() - 5).getSharesOutstanding();
        return recent.compareTo(yearAgo) > 0;
    }

    private void add(List<EvidenceItem> ev, String category, String metric, String value, String interpretation,
                     String direction, int confidence, String source, LocalDate asOf) {
        ev.add(new EvidenceItem(category, metric, value, interpretation, direction, confidence, source, asOf));
    }
}
