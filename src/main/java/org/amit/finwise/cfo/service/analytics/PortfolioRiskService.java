package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis;
import org.apache.commons.math3.stat.descriptive.moment.Skewness;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes a full RiskDecomposition for a user's equity portfolio.
 *
 * All risk metrics are computed from adjustedClose-based daily returns so that
 * corporate actions don't inflate volatility estimates.
 *
 * Weights are market-value weights (Investment.currentValue), not cost-basis weights.
 * Beta and tracking-error use Nifty 50 (^NSEI) as the benchmark.
 *
 * A LOW_CONFIDENCE flag is set when fewer than MIN_OBSERVATIONS common trading days
 * exist in the covariance window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioRiskService {

    private final ReturnSeriesService returnSeriesService;
    private final CovarianceEngine covarianceEngine;
    private final InvestmentRepository investmentRepository;
    private final RiskProperties riskProperties;

    private static final double SQRT_252 = Math.sqrt(252.0);
    private static final double ANNUAL_DAYS = 252.0;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Computes the full risk decomposition for the user's portfolio.
     * Returns empty when the portfolio has insufficient equity history.
     */
    public Optional<RiskDecomposition> compute(String userId) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return Optional.empty();

        // Only equities with a symbol and a current market value can be risk-modelled
        List<Investment> equities = investments.stream()
                .filter(inv -> inv.getSymbol() != null && !inv.getSymbol().isBlank())
                .filter(inv -> inv.getCurrentValue() != null
                        && inv.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (equities.isEmpty()) return Optional.empty();

        BigDecimal totalValue = equities.stream()
                .map(Investment::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalValue.compareTo(BigDecimal.ZERO) == 0) return Optional.empty();

        // Market-value weights and sector map
        Map<String, Double> rawWeights       = new LinkedHashMap<>();
        Map<String, String> symbolToSector   = new HashMap<>();
        for (Investment inv : equities) {
            String sym = inv.getSymbol().toUpperCase();
            double w = inv.getCurrentValue().doubleValue() / totalValue.doubleValue();
            rawWeights.put(sym, w);
            if (inv.getSector() != null) symbolToSector.put(sym, inv.getSector());
        }

        List<String> symbols = new ArrayList<>(rawWeights.keySet());
        List<String> allSymbols = new ArrayList<>(symbols);
        allSymbols.add(StockPriceService.NIFTY_SYMBOL);

        // ── Return series ─────────────────────────────────────────────────────
        LocalDate since = LocalDate.now().minusDays(riskProperties.getLookbackDays());
        Map<String, NavigableMap<LocalDate, Double>> allReturns =
                returnSeriesService.getReturnSeries(allSymbols, since);

        NavigableMap<LocalDate, Double> niftyReturns = allReturns.get(StockPriceService.NIFTY_SYMBOL);

        Map<String, NavigableMap<LocalDate, Double>> stockReturns = allReturns.entrySet().stream()
                .filter(e -> !StockPriceService.NIFTY_SYMBOL.equals(e.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        List<String> included = new ArrayList<>(stockReturns.keySet());
        List<String> excluded = symbols.stream().filter(s -> !included.contains(s)).toList();

        if (included.size() < 2) {
            log.warn("[RiskEngine] Only {} symbols with sufficient price history; need ≥2", included.size());
            return Optional.empty();
        }

        List<String> dataQualityNotes = new ArrayList<>();

        // Re-normalize weights to included-only market values. The dropped weight is
        // reported explicitly — silently re-normalizing understates risk when the
        // excluded names are the volatile ones.
        double includedTotalWeight = included.stream()
                .mapToDouble(s -> rawWeights.getOrDefault(s, 0.0)).sum();
        double excludedWeightPct = Math.max(0.0, 1.0 - includedTotalWeight);
        if (!excluded.isEmpty()) {
            dataQualityNotes.add(String.format(
                    "EXCLUDED_WEIGHT: %.1f%% of portfolio value excluded from covariance estimation (%s)",
                    excludedWeightPct * 100, String.join(", ", excluded)));
        }
        Map<String, Double> normalizedWeights = new LinkedHashMap<>();
        for (String sym : included) {
            normalizedWeights.put(sym, rawWeights.getOrDefault(sym, 0.0) / includedTotalWeight);
        }

        // ── Covariance matrix ─────────────────────────────────────────────────
        CovarianceEngine.AlignedSeries aligned =
                covarianceEngine.align(stockReturns, ReturnSeriesService.MIN_OBSERVATIONS);

        if (aligned == null) {
            log.warn("[RiskEngine] Insufficient aligned observations — returning empty");
            return Optional.empty();
        }

        int n = aligned.symbols().size();

        // Ledoit-Wolf shrinkage stabilizes the matrix when N parameters approach
        // T observations. With only 2 assets the single off-diagonal is estimated
        // directly — shrinking toward "average correlation" would be circular.
        double[][] covMatrix;
        Double shrinkageIntensity = null;
        if (riskProperties.isShrinkageEnabled() && n >= 3) {
            CovarianceEngine.ShrinkageResult sr = covarianceEngine.shrunkCovariance(aligned);
            covMatrix = sr.sigma();
            shrinkageIntensity = sr.delta();
        } else {
            covMatrix = covarianceEngine.covarianceMatrix(aligned);
        }

        // Weight vector in aligned symbol order
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = normalizedWeights.getOrDefault(aligned.symbols().get(i), 0.0);
        }

        // ── Portfolio variance: σ²_p = w^T Σ w ───────────────────────────────
        double[] covW = new double[n]; // (Σw) vector
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                covW[i] += covMatrix[i][j] * w[j];
            }
        }
        double portfolioVariance = 0;
        for (int i = 0; i < n; i++) portfolioVariance += w[i] * covW[i];

        double dailyVol     = Math.sqrt(portfolioVariance);
        double annualizedVol = dailyVol * SQRT_252;

        // Per-symbol individual daily vols (sqrt of diagonal)
        double[] symbolVols = new double[n];
        for (int i = 0; i < n; i++) symbolVols[i] = Math.sqrt(covMatrix[i][i]);

        // ── Beta via Nifty benchmark ──────────────────────────────────────────
        Map<String, Double> perHoldingBeta = new LinkedHashMap<>();
        double portfolioBeta = 0.0;
        if (niftyReturns != null) {
            for (int i = 0; i < n; i++) {
                String sym = aligned.symbols().get(i);
                NavigableMap<LocalDate, Double> stockSeries = stockReturns.get(sym);
                if (stockSeries == null) continue;
                CovarianceEngine.BetaStats bs = covarianceEngine.betaStats(stockSeries, niftyReturns);
                // Unknown beta defaults to the market prior 1.0, not 0.0 — a zero
                // would silently understate portfolio beta.
                double beta;
                if (Double.isNaN(bs.beta())) {
                    beta = 1.0;
                    dataQualityNotes.add("BETA_IMPUTED: " + sym
                            + " — insufficient benchmark overlap; market beta 1.0 assumed");
                } else {
                    beta = bs.beta();
                }
                perHoldingBeta.put(sym, beta);
                portfolioBeta += w[i] * beta;
            }
        }

        // ── Risk contributions ─────────────────────────────────────────────────
        // MCRᵢ = (Σw)ᵢ / σ_p
        // CCRᵢ = wᵢ × MCRᵢ     (Σ CCRᵢ = σ_p)
        // pᵢ   = CCRᵢ / σ_p    (Σ pᵢ = 1)
        List<RiskDecomposition.RiskContributor> contributors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String sym = aligned.symbols().get(i);
            double wi  = w[i];
            double mcr = dailyVol > 0 ? covW[i] / dailyVol : 0;
            double ccr = wi * mcr;
            double pct = dailyVol > 0 ? ccr / dailyVol : 0;
            contributors.add(new RiskDecomposition.RiskContributor(
                    sym, wi,
                    perHoldingBeta.getOrDefault(sym, 0.0),
                    mcr, ccr, pct
            ));
        }
        contributors.sort(Comparator.comparingDouble(
                RiskDecomposition.RiskContributor::percentContributionToRisk).reversed());

        // ── Historical portfolio returns ───────────────────────────────────────
        List<LocalDate> commonDates = aligned.dates();
        int T = commonDates.size();
        double[] portfolioReturns = new double[T];
        for (int t = 0; t < T; t++) {
            for (int i = 0; i < n; i++) {
                portfolioReturns[t] += w[i] * aligned.data()[t][i];
            }
        }

        // ── VaR / CVaR ────────────────────────────────────────────────────────
        double V = totalValue.doubleValue() * (includedTotalWeight); // market value of included symbols
        double var95Parametric = 1.645 * dailyVol * V;
        double var99Parametric = 2.326 * dailyVol * V;

        // Cornish-Fisher VaR: Gaussian z-scores understate tail risk for the
        // fat-tailed, negatively skewed returns typical of Indian equities.
        // z_CF = z + (z²−1)/6·γ₁ + (z³−3z)/24·γ₂ − (2z³−5z)/36·γ₁²
        // The expansion is a Gram-Charlier truncation, only reliable in a bounded
        // skew/kurtosis domain — outside it the adjusted quantile can fall INSIDE
        // the Gaussian one, so we substitute the parametric figure instead.
        double skewness = new Skewness().evaluate(portfolioReturns);
        double excessKurtosis = new Kurtosis().evaluate(portfolioReturns); // Commons Math returns excess kurtosis
        double var95CornishFisher;
        double var99CornishFisher;
        if (cornishFisherValid(skewness, excessKurtosis)) {
            var95CornishFisher = cornishFisherZ(-1.645, skewness, excessKurtosis) * -dailyVol * V;
            var99CornishFisher = cornishFisherZ(-2.326, skewness, excessKurtosis) * -dailyVol * V;
        } else {
            var95CornishFisher = var95Parametric;
            var99CornishFisher = var99Parametric;
            dataQualityNotes.add(String.format(
                    "CF_INVALID: skew=%.2f, exKurt=%.2f outside validity domain (|skew|≤2.5, exKurt≤10); parametric VaR substituted",
                    skewness, excessKurtosis));
        }

        double[] sortedReturns = portfolioReturns.clone();
        Arrays.sort(sortedReturns);
        double historicalReturn95 = interpolatedQuantile(sortedReturns, 0.05);
        double var95Historical = -historicalReturn95 * V;

        // CVaR = E[loss | loss strictly worse than VaR95]. When nothing lies
        // beyond the quantile, fall back to VaR95.
        double cvarSum = 0;
        int cvarCount = 0;
        for (double r : sortedReturns) {
            if (r >= historicalReturn95) break;
            cvarSum += r;
            cvarCount++;
        }
        double cvar95 = cvarCount > 0 ? -(cvarSum / cvarCount) * V : var95Historical;

        // ── Diversification ratio: (Σ wᵢ σᵢ) / σ_p ──────────────────────────
        double weightedVolSum = 0;
        for (int i = 0; i < n; i++) weightedVolSum += w[i] * symbolVols[i];
        double diversificationRatio = dailyVol > 0 ? weightedVolSum / dailyVol : 1.0;

        // ── ENB = 1 / Σ pᵢ² ──────────────────────────────────────────────────
        double pctSquaredSum = contributors.stream()
                .mapToDouble(c -> c.percentContributionToRisk() * c.percentContributionToRisk())
                .sum();
        double enb = pctSquaredSum > 0 ? 1.0 / pctSquaredSum : 1.0;

        // ── Concentration HHIs ────────────────────────────────────────────────
        double nameHHI = normalizedWeights.values().stream()
                .mapToDouble(wi -> wi * wi).sum();
        Map<String, Double> sectorWeights = new HashMap<>();
        for (Map.Entry<String, Double> e : normalizedWeights.entrySet()) {
            String sector = symbolToSector.getOrDefault(e.getKey(), "Unknown");
            sectorWeights.merge(sector, e.getValue(), Double::sum);
        }
        double sectorHHI = sectorWeights.values().stream()
                .mapToDouble(sw -> sw * sw).sum();

        // ── Sharpe and Sortino ─────────────────────────────────────────────────
        double meanDailyReturn  = StatUtils.mean(portfolioReturns);
        // Geometric (compound) annualization; arithmetic mean × 252 understates
        // the annual figure for high-return portfolios.
        double annualizedReturn = Math.pow(1.0 + meanDailyReturn, ANNUAL_DAYS) - 1.0;
        double riskFreeRate = riskProperties.getRiskFreeRate();
        double sharpe = annualizedVol > 0
                ? (annualizedReturn - riskFreeRate) / annualizedVol : Double.NaN;

        // Downside deviation with MAR = risk-free rate (not zero):
        // DD = sqrt( (1/T) × Σ min(r_t − r_f_daily, 0)² ) × √252
        double dailyRf = riskFreeRate / ANNUAL_DAYS;
        double sumSquaredDownside = Arrays.stream(portfolioReturns)
                .filter(r -> r < dailyRf)
                .map(r -> (r - dailyRf) * (r - dailyRf))
                .sum();
        double annualizedDownsideDev = T > 0
                ? Math.sqrt(sumSquaredDownside / T) * SQRT_252 : Double.NaN;
        double sortino = !Double.isNaN(annualizedDownsideDev) && annualizedDownsideDev > 0
                ? (annualizedReturn - riskFreeRate) / annualizedDownsideDev : Double.NaN;

        // ── Tracking error vs Nifty ───────────────────────────────────────────
        double trackingError = Double.NaN;
        if (niftyReturns != null) {
            // Build a date→index map for O(1) look-up
            Map<LocalDate, Integer> dateIdx = new HashMap<>();
            for (int t = 0; t < T; t++) dateIdx.put(commonDates.get(t), t);

            List<Double> activeReturns = new ArrayList<>();
            for (Map.Entry<LocalDate, Double> e : niftyReturns.entrySet()) {
                Integer idx = dateIdx.get(e.getKey());
                if (idx != null) {
                    activeReturns.add(portfolioReturns[idx] - e.getValue());
                }
            }
            if (activeReturns.size() > 1) {
                double[] ar = activeReturns.stream().mapToDouble(Double::doubleValue).toArray();
                trackingError = Math.sqrt(StatUtils.variance(ar)) * SQRT_252;
            }
        }

        // ── Headline ──────────────────────────────────────────────────────────
        String topNames = contributors.stream().limit(2)
                .map(RiskDecomposition.RiskContributor::symbol)
                .collect(Collectors.joining(", "));
        double top2Pct = contributors.stream().limit(2)
                .mapToDouble(c -> c.percentContributionToRisk() * 100).sum();
        String headline = String.format(
                "%.1f%% of portfolio variance comes from %s; effective bets = %.1f",
                top2Pct, topNames, enb);

        boolean isLowConfidence = T < ReturnSeriesService.MIN_OBSERVATIONS
                || !excluded.isEmpty()
                || excludedWeightPct > 0.25;

        log.info("[RiskEngine] Computed: vol={}%, beta={}, ENB={}, VaR95=₹{}, excluded={}",
                String.format("%.1f", annualizedVol * 100),
                String.format("%.2f", portfolioBeta),
                String.format("%.1f", enb),
                String.format("%.0f", var95Parametric),
                excluded);

        return Optional.of(new RiskDecomposition(
                included, excluded,
                excludedWeightPct,
                Collections.unmodifiableList(dataQualityNotes),
                T,
                commonDates.getFirst(), commonDates.getLast(),
                isLowConfidence,
                annualizedVol, dailyVol,
                shrinkageIntensity,
                portfolioBeta, perHoldingBeta,
                var95Parametric, var99Parametric,
                var95CornishFisher, var99CornishFisher,
                var95Historical, cvar95,
                skewness, excessKurtosis,
                Collections.unmodifiableList(contributors),
                diversificationRatio, enb, nameHHI, sectorHHI,
                sharpe, sortino, trackingError,
                headline
        ));
    }

    /**
     * Cornish-Fisher expansion of a Gaussian quantile for skewness γ₁ and
     * excess kurtosis γ₂. For the loss tail pass a negative z (e.g. −1.645).
     */
    static double cornishFisherZ(double z, double skewness, double excessKurtosis) {
        if (Double.isNaN(skewness) || Double.isNaN(excessKurtosis)) return z;
        return z
                + (z * z - 1) / 6.0 * skewness
                + (z * z * z - 3 * z) / 24.0 * excessKurtosis
                - (2 * z * z * z - 5 * z) / 36.0 * skewness * skewness;
    }

    /**
     * Validity domain of the Cornish-Fisher expansion. Beyond |γ₁| 2.5 or γ₂ 10
     * the truncated series is non-monotone and can place the adjusted quantile
     * inside the Gaussian one. NaN moments pass through (cornishFisherZ degrades
     * to the plain quantile in that case).
     */
    static boolean cornishFisherValid(double skewness, double excessKurtosis) {
        return !(Math.abs(skewness) > 2.5 || excessKurtosis > 10.0);
    }

    /**
     * Linear-interpolated empirical quantile (Hyndman-Fan R-7, the R default):
     * h = (T−1)p; q = x_⌊h⌋ + (h−⌊h⌋)(x_⌊h⌋₊₁ − x_⌊h⌋) on the 0-indexed sorted array.
     */
    static double interpolatedQuantile(double[] sortedAscending, double p) {
        int T = sortedAscending.length;
        if (T == 0) return Double.NaN;
        if (T == 1) return sortedAscending[0];
        double h = (T - 1) * p;
        int lo = (int) Math.floor(h);
        if (lo + 1 >= T) return sortedAscending[T - 1];
        return sortedAscending[lo] + (h - lo) * (sortedAscending[lo + 1] - sortedAscending[lo]);
    }
}
