package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.apache.commons.math3.stat.StatUtils;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${cfo.risk.risk-free-rate:0.071}")
    private double riskFreeRate;           // 10Y G-sec yield (annualized)

    @Value("${cfo.risk.lookback-days:365}")
    private int lookbackDays;

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
        LocalDate since = LocalDate.now().minusDays(lookbackDays);
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

        // Re-normalize weights to included-only market values
        double includedTotalWeight = included.stream()
                .mapToDouble(s -> rawWeights.getOrDefault(s, 0.0)).sum();
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

        double[][] covMatrix = covarianceEngine.covarianceMatrix(aligned);
        int n = aligned.symbols().size();

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
                double beta = Double.isNaN(bs.beta()) ? 0.0 : bs.beta();
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

        double[] sortedReturns = portfolioReturns.clone();
        Arrays.sort(sortedReturns);
        int var95Idx = Math.max(0, (int) Math.floor(0.05 * T) - 1);
        double historicalReturn95 = sortedReturns[var95Idx];
        double var95Historical = -historicalReturn95 * V;

        double cvarSum = 0;
        for (int t = 0; t <= var95Idx; t++) cvarSum += sortedReturns[t];
        double cvar95 = var95Idx >= 0 ? -(cvarSum / (var95Idx + 1)) * V : 0;

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
        double annualizedReturn = meanDailyReturn * ANNUAL_DAYS;
        double sharpe = annualizedVol > 0
                ? (annualizedReturn - riskFreeRate) / annualizedVol : Double.NaN;

        // Downside deviation: sqrt(E[min(r,0)²]) × √252
        double sumSquaredDownside = Arrays.stream(portfolioReturns)
                .filter(r -> r < 0)
                .map(r -> r * r)
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

        boolean isLowConfidence = T < ReturnSeriesService.MIN_OBSERVATIONS || !excluded.isEmpty();

        log.info("[RiskEngine] Computed: vol={}%, beta={}, ENB={}, VaR95=₹{}, excluded={}",
                String.format("%.1f", annualizedVol * 100),
                String.format("%.2f", portfolioBeta),
                String.format("%.1f", enb),
                String.format("%.0f", var95Parametric),
                excluded);

        return Optional.of(new RiskDecomposition(
                included, excluded, T,
                commonDates.getFirst(), commonDates.getLast(),
                isLowConfidence,
                annualizedVol, dailyVol,
                portfolioBeta, perHoldingBeta,
                var95Parametric, var99Parametric, var95Historical, cvar95,
                Collections.unmodifiableList(contributors),
                diversificationRatio, enb, nameHHI, sectorHHI,
                sharpe, sortino, trackingError,
                headline
        ));
    }
}
