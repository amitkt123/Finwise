package org.amit.finwise.cfo.service.analytics;

import lombok.extern.slf4j.Slf4j;
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
