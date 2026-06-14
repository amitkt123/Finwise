package org.amit.finwise.cfo.service.analytics;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.stat.StatUtils;
import org.apache.commons.math3.stat.correlation.Covariance;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * Computes covariance matrices and beta statistics from aligned daily-return series.
 *
 * All inputs come from ReturnSeriesService to ensure a single canonical return definition.
 * Apache Commons Math 3 handles the matrix algebra.
 */
@Slf4j
@Component
public class CovarianceEngine {

    /**
     * Aligns all return series on the intersection of trading dates.
     * Returns null when fewer than {@code minObs} common dates exist (LOW_CONFIDENCE).
     *
     * @param returnSeries symbol → sorted date→return map (from ReturnSeriesService)
     * @param minObs       minimum number of aligned observations required
     * @return AlignedSeries with data[t][j] = return of symbol j at time t; null if insufficient
     */
    public AlignedSeries align(Map<String, NavigableMap<LocalDate, Double>> returnSeries,
                               int minObs) {
        if (returnSeries.isEmpty()) return null;

        Set<LocalDate> commonDates = null;
        for (var entry : returnSeries.entrySet()) {
            if (commonDates == null) {
                commonDates = new TreeSet<>(entry.getValue().keySet());
            } else {
                commonDates.retainAll(entry.getValue().keySet());
            }
        }

        if (commonDates == null || commonDates.size() < minObs) {
            log.warn("[CovEngine] {} common dates across {} symbols (min {})",
                    commonDates == null ? 0 : commonDates.size(), returnSeries.size(), minObs);
            return null;
        }

        List<String> symbols = new ArrayList<>(returnSeries.keySet());
        List<LocalDate> dates = new ArrayList<>(commonDates); // ascending — TreeSet guarantees order
        int n = symbols.size();
        int T = dates.size();

        double[][] data = new double[T][n];
        for (int j = 0; j < n; j++) {
            NavigableMap<LocalDate, Double> series = returnSeries.get(symbols.get(j));
            for (int t = 0; t < T; t++) {
                data[t][j] = series.get(dates.get(t));
            }
        }

        return new AlignedSeries(symbols, dates, data);
    }

    /**
     * Computes an n×n sample covariance matrix from aligned return data.
     * Uses N-1 denominator (unbiased sample covariance).
     * Entry [i][j] = Cov(r_i, r_j).
     */
    public double[][] covarianceMatrix(AlignedSeries aligned) {
        Covariance cov = new Covariance(aligned.data());
        return cov.getCovarianceMatrix().getData();
    }

    /**
     * Ledoit-Wolf shrunk covariance matrix (constant-correlation target) on the
     * unbiased N-1 scale, with a positive-semidefinite safety net.
     *
     * A convex combination of two PSD matrices is PSD in exact arithmetic, but
     * near-singular sample matrices can carry tiny negative eigenvalues from
     * floating-point error into downstream quadratic forms — clip them to zero.
     */
    public ShrinkageResult shrunkCovariance(AlignedSeries aligned) {
        LedoitWolfShrinkage.Result r = LedoitWolfShrinkage.shrink(aligned.data());
        return new ShrinkageResult(ensurePositiveSemiDefinite(r.sigma()), r.delta());
    }

    private static double[][] ensurePositiveSemiDefinite(double[][] m) {
        EigenDecomposition eig = new EigenDecomposition(new Array2DRowRealMatrix(m));
        double[] eigenvalues = eig.getRealEigenvalues();
        boolean needsClipping = false;
        for (double ev : eigenvalues) {
            if (ev < -1e-10) { needsClipping = true; break; }
        }
        if (!needsClipping) return m;

        log.warn("[CovEngine] Covariance matrix not PSD (min eigenvalue {}); clipping",
                Arrays.stream(eigenvalues).min().orElse(Double.NaN));
        RealMatrix v = eig.getV();
        double[][] d = new double[eigenvalues.length][eigenvalues.length];
        for (int i = 0; i < eigenvalues.length; i++) d[i][i] = Math.max(0, eigenvalues[i]);
        return v.multiply(new Array2DRowRealMatrix(d)).multiply(v.transpose()).getData();
    }

    /**
     * Computes beta and covariance statistics for a single stock vs. a benchmark.
     * Uses only the intersection of dates between the two series.
     *
     * @return BetaStats with beta = NaN when insufficient overlap
     */
    public BetaStats betaStats(NavigableMap<LocalDate, Double> stockReturns,
                               NavigableMap<LocalDate, Double> benchmarkReturns) {
        Set<LocalDate> common = new TreeSet<>(stockReturns.keySet());
        common.retainAll(benchmarkReturns.keySet());

        if (common.size() < ReturnSeriesService.MIN_OBSERVATIONS) {
            return new BetaStats(Double.NaN, Double.NaN, Double.NaN, common.size());
        }

        double[] stock = new double[common.size()];
        double[] bench = new double[common.size()];
        int i = 0;
        for (LocalDate d : common) {
            stock[i] = stockReturns.get(d);
            bench[i] = benchmarkReturns.get(d);
            i++;
        }

        double meanStock = StatUtils.mean(stock);
        double meanBench = StatUtils.mean(bench);
        double varBench  = StatUtils.variance(bench);  // uses N-1

        // Sample covariance Cov(stock, bench)
        double cov = 0;
        for (int j = 0; j < stock.length; j++) {
            cov += (stock[j] - meanStock) * (bench[j] - meanBench);
        }
        cov /= (stock.length - 1);

        double beta = varBench > 0 ? cov / varBench : Double.NaN;
        return new BetaStats(beta, cov, varBench, common.size());
    }

    /**
     * Dimson (1979) beta for thinly-traded names (BPR-9).
     *
     * Illiquid midcaps trade infrequently, so a stale closing price makes their daily
     * returns lag the market — naive OLS beta is biased <b>downward</b> and covariance is
     * understated. Dimson corrects this by summing the slopes from regressing the stock's
     * return on lead, contemporaneous, and lagged market returns:
     *
     *   r_stock(t) = α + Σ_{k=−L..+L} β_k · r_mkt(t+k) + ε,   β_Dimson = Σ_k β_k
     *
     * Returns a {@link DimsonBeta} with the summed beta and naive beta side by side; NaN
     * beta when fewer than {@code MIN_OBSERVATIONS} aligned trips exist for the regression.
     *
     * @param leadLag number of lead/lag market terms each side (1 is standard for daily data)
     */
    public DimsonBeta dimsonBetaStats(NavigableMap<LocalDate, Double> stockReturns,
                                      NavigableMap<LocalDate, Double> benchmarkReturns,
                                      int leadLag) {
        double naive = betaStats(stockReturns, benchmarkReturns).beta();
        if (leadLag < 1) {
            return new DimsonBeta(naive, naive, 0, 0);
        }
        // Common dates in ascending order; lead/lag are by trading-day position.
        List<LocalDate> common = new ArrayList<>(stockReturns.keySet());
        common.retainAll(benchmarkReturns.keySet());
        Collections.sort(common);
        int n = common.size();
        int usable = n - 2 * leadLag;
        int k = 2 * leadLag + 1;
        if (usable < ReturnSeriesService.MIN_OBSERVATIONS || usable <= k + 1) {
            return new DimsonBeta(naive, Double.NaN, leadLag, Math.max(0, usable));
        }

        double[] benchAll = new double[n];
        double[] stockAll = new double[n];
        for (int i = 0; i < n; i++) {
            stockAll[i] = stockReturns.get(common.get(i));
            benchAll[i] = benchmarkReturns.get(common.get(i));
        }

        double[] y = new double[usable];
        double[][] x = new double[usable][k];
        for (int t = leadLag; t < n - leadLag; t++) {
            int row = t - leadLag;
            y[row] = stockAll[t];
            int col = 0;
            for (int off = -leadLag; off <= leadLag; off++) {
                x[row][col++] = benchAll[t + off];
            }
        }

        try {
            org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression ols =
                    new org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression();
            ols.newSampleData(y, x);
            double[] b = ols.estimateRegressionParameters(); // [α, β_{-L}..β_{+L}]
            double dimson = 0;
            for (int j = 1; j <= k; j++) dimson += b[j];
            return new DimsonBeta(naive, dimson, leadLag, usable);
        } catch (Exception e) {
            log.debug("[Dimson] regression failed: {}", e.getMessage());
            return new DimsonBeta(naive, Double.NaN, leadLag, usable);
        }
    }

    /**
     * Downside beta using only dates where benchmark return is negative.
     */
    public BetaStats downsideBetaStats(NavigableMap<LocalDate, Double> stockReturns,
                                       NavigableMap<LocalDate, Double> benchmarkReturns) {
        NavigableMap<LocalDate, Double> stockDownside = new TreeMap<>();
        NavigableMap<LocalDate, Double> benchmarkDownside = new TreeMap<>();
        for (LocalDate date : stockReturns.keySet()) {
            Double benchmarkReturn = benchmarkReturns.get(date);
            if (benchmarkReturn != null && benchmarkReturn < 0) {
                stockDownside.put(date, stockReturns.get(date));
                benchmarkDownside.put(date, benchmarkReturn);
            }
        }
        return betaStats(stockDownside, benchmarkDownside);
    }

    /**
     * Annualized EWMA volatility with RiskMetrics lambda=0.94 by default.
     */
    public double ewmaVolatilityAnnualized(NavigableMap<LocalDate, Double> returns, double lambda) {
        if (returns == null || returns.isEmpty() || lambda <= 0 || lambda >= 1) return Double.NaN;
        // Seed with the variance of the first ~20 observations only, then recurse over
        // the remainder. Seeding with the FULL sample leaks future observations into
        // the recursion; seeding with r_0² anchors to one observation that decays
        // slowly at lambda=0.94.
        double[] all = returns.values().stream().mapToDouble(Double::doubleValue).toArray();
        int seedLen = Math.min(EWMA_SEED_OBSERVATIONS, all.length);
        double variance = seedLen > 1
                ? StatUtils.variance(Arrays.copyOfRange(all, 0, seedLen))
                : all[0] * all[0];
        for (int i = seedLen; i < all.length; i++) {
            variance = lambda * variance + (1 - lambda) * all[i] * all[i];
        }
        return Math.sqrt(variance) * Math.sqrt(252.0);
    }

    static final int EWMA_SEED_OBSERVATIONS = 20;

    /**
     * Maximum drawdown from ascending adjusted-close prices.
     * Returns a negative number, e.g. -0.25 for a 25% drawdown.
     */
    public double maxDrawdown(NavigableMap<LocalDate, Double> prices) {
        if (prices == null || prices.isEmpty()) return Double.NaN;
        double runningMax = Double.NEGATIVE_INFINITY;
        double maxDrawdown = 0.0;
        for (double price : prices.values()) {
            if (price <= 0) continue;
            runningMax = Math.max(runningMax, price);
            if (runningMax > 0) {
                maxDrawdown = Math.min(maxDrawdown, price / runningMax - 1.0);
            }
        }
        return maxDrawdown;
    }

    /**
     * Rolling beta keyed by the window end date.
     */
    public NavigableMap<LocalDate, Double> rollingBeta(NavigableMap<LocalDate, Double> stockReturns,
                                                       NavigableMap<LocalDate, Double> benchmarkReturns,
                                                       int window) {
        NavigableMap<LocalDate, Double> result = new TreeMap<>();
        if (window < 2) return result;
        List<LocalDate> common = new ArrayList<>(stockReturns.keySet());
        common.retainAll(benchmarkReturns.keySet());
        Collections.sort(common);
        for (int endExclusive = window; endExclusive <= common.size(); endExclusive++) {
            NavigableMap<LocalDate, Double> stockWindow = new TreeMap<>();
            NavigableMap<LocalDate, Double> benchWindow = new TreeMap<>();
            for (LocalDate date : common.subList(endExclusive - window, endExclusive)) {
                stockWindow.put(date, stockReturns.get(date));
                benchWindow.put(date, benchmarkReturns.get(date));
            }
            BetaStats stats = betaStats(stockWindow, benchWindow);
            result.put(common.get(endExclusive - 1), stats.beta());
        }
        return result;
    }

    /**
     * Correlation during the worst benchmark-return days.
     *
     * @param worstFraction fraction of common dates to keep after sorting benchmark returns ascending.
     */
    public double stressedCorrelation(NavigableMap<LocalDate, Double> stockReturns,
                                      NavigableMap<LocalDate, Double> benchmarkReturns,
                                      double worstFraction) {
        List<LocalDate> common = new ArrayList<>(stockReturns.keySet());
        common.retainAll(benchmarkReturns.keySet());
        if (common.size() < 2 || worstFraction <= 0 || worstFraction > 1) return Double.NaN;
        common.sort(Comparator.comparingDouble(benchmarkReturns::get));
        int keep = Math.max(2, (int) Math.ceil(common.size() * worstFraction));
        double[] stock = new double[keep];
        double[] bench = new double[keep];
        for (int i = 0; i < keep; i++) {
            LocalDate date = common.get(i);
            stock[i] = stockReturns.get(date);
            bench[i] = benchmarkReturns.get(date);
        }
        double meanStock = StatUtils.mean(stock);
        double meanBench = StatUtils.mean(bench);
        double cov = 0;
        for (int i = 0; i < keep; i++) {
            cov += (stock[i] - meanStock) * (bench[i] - meanBench);
        }
        cov /= (keep - 1);
        double stockStd = Math.sqrt(StatUtils.variance(stock));
        double benchStd = Math.sqrt(StatUtils.variance(bench));
        return stockStd > 0 && benchStd > 0 ? cov / (stockStd * benchStd) : Double.NaN;
    }

    /**
     * Average traded value proxy from close price and volume on overlapping dates.
     */
    public double averageTradedValue(NavigableMap<LocalDate, Double> closePrices,
                                     NavigableMap<LocalDate, Long> volumes) {
        if (closePrices == null || volumes == null || closePrices.isEmpty() || volumes.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        int count = 0;
        for (Map.Entry<LocalDate, Double> priceEntry : closePrices.entrySet()) {
            Long volume = volumes.get(priceEntry.getKey());
            Double price = priceEntry.getValue();
            if (volume != null && volume > 0 && price != null && price > 0) {
                sum += price * volume;
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    /**
     * Computes the marginal impact of adding a hypothetical position to the portfolio.
     * Returns portfolio's existing volatility, volatility if the candidate is added,
     * delta volatility, and correlation of the candidate to the existing portfolio.
     *
     * @param returnSeries all return series (existing holdings + candidate)
     * @param existingWeights market-value weights of existing holdings (sum to 1.0)
     * @param candidateSymbol the symbol to add
     * @param hypotheticalWeight weight of the new position (typically 0.05); existing weights scale to (1-hw)
     * @return MarginalImpact record; fields are NaN if insufficient data
     */
    public MarginalImpact marginalVolImpact(
            Map<String, NavigableMap<LocalDate, Double>> returnSeries,
            Map<String, Double> existingWeights,
            String candidateSymbol,
            double hypotheticalWeight) {

        // Align all series
        AlignedSeries aligned = align(returnSeries, ReturnSeriesService.MIN_OBSERVATIONS);
        if (aligned == null) {
            return MarginalImpact.unavailable();
        }

        // Find indices for existing holdings and candidate
        int candidateIdx = aligned.symbols().indexOf(candidateSymbol.toUpperCase());
        if (candidateIdx < 0) {
            return MarginalImpact.unavailable();
        }

        // Build portfolio return for existing holdings
        int T = aligned.dates().size();
        double[] portfolioReturnOld = new double[T];
        for (int t = 0; t < T; t++) {
            double ret = 0;
            for (String sym : existingWeights.keySet()) {
                int idx = aligned.symbols().indexOf(sym.toUpperCase());
                if (idx >= 0) {
                    ret += existingWeights.get(sym) * aligned.data()[t][idx];
                }
            }
            portfolioReturnOld[t] = ret;
        }

        // Compute existing portfolio volatility
        double varOld = StatUtils.variance(portfolioReturnOld);
        double sigmaOld = Math.sqrt(varOld * 252); // annualized

        // Build new portfolio return: (1-hw) * old + hw * candidate
        double[] portfolioReturnNew = new double[T];
        double[] candidateReturn = new double[T];
        for (int t = 0; t < T; t++) {
            candidateReturn[t] = aligned.data()[t][candidateIdx];
            portfolioReturnNew[t] = (1 - hypotheticalWeight) * portfolioReturnOld[t] +
                                    hypotheticalWeight * candidateReturn[t];
        }

        // Compute new portfolio volatility
        double varNew = StatUtils.variance(portfolioReturnNew);
        double sigmaNew = Math.sqrt(varNew * 252);

        // Compute correlation: ρ = Cov(candidate, portfolioOld) / (σ_cand × σ_old).
        // All three quantities must be in the SAME (daily) units — cov is the daily
        // sample covariance, so the sigmas here are daily, not the annualized ones above.
        double meanCand = StatUtils.mean(candidateReturn);
        double meanOldPort = StatUtils.mean(portfolioReturnOld);
        double cov = 0;
        for (int t = 0; t < T; t++) {
            cov += (candidateReturn[t] - meanCand) * (portfolioReturnOld[t] - meanOldPort);
        }
        cov /= (T - 1);

        double sigmaCandDaily = Math.sqrt(StatUtils.variance(candidateReturn));
        double sigmaOldDaily  = Math.sqrt(varOld);
        double rho = (sigmaCandDaily > 0 && sigmaOldDaily > 0)
                ? cov / (sigmaCandDaily * sigmaOldDaily) : Double.NaN;

        return new MarginalImpact(sigmaOld, sigmaNew, sigmaNew - sigmaOld, rho, hypotheticalWeight);
    }

    // ── Result types ──────────────────────────────────────────────────────────

    public record AlignedSeries(
            List<String> symbols,
            List<LocalDate> dates,
            double[][] data          // data[t][j] = return of symbol j at time t
    ) {}

    public record BetaStats(
            double beta,
            double covariance,
            double benchmarkVariance,
            int observations
    ) {}

    /**
     * Dimson lead/lag beta for illiquid names (BPR-9). {@code naiveBeta} is the plain
     * OLS slope (stale-price-biased downward); {@code dimsonBeta} sums the lead/lag
     * slopes to recover the true exposure. {@code dimsonBeta} is NaN when too few
     * trips align for the regression — callers then keep the naive beta.
     */
    public record DimsonBeta(
            double naiveBeta,
            double dimsonBeta,
            int leadLag,
            int observations
    ) {
        public boolean isUsable() {
            return !Double.isNaN(dimsonBeta);
        }
    }

    public record ShrinkageResult(
            double[][] sigma,   // shrunk covariance, unbiased (N-1) scale, PSD
            double delta        // Ledoit-Wolf intensity δ ∈ [0,1]
    ) {}

    public record MarginalImpact(
            double existingVolAnnualized,
            double newVolAnnualized,
            double deltaVol,                // newVol - existingVol (positive = more risk)
            double correlationToPortfolio,  // ρ(candidate, existing portfolio)
            double hypotheticalWeight
    ) {
        public static MarginalImpact unavailable() {
            return new MarginalImpact(Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.05);
        }
    }
}
