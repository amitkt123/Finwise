package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.VolForecast;
import org.amit.finwise.cfo.service.analytics.GarchService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.simulation.dto.MonteCarloInterval;
import org.amit.finwise.simulation.dto.ProjectionResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ForwardProjectionEngine {

    private final GarchService garchService;
    private final ReturnSeriesService returnSeriesService;
    private final ScenarioBandService scenarioBandService;
    private final RiskProperties riskProperties;

    static final int MC_PATHS = 1_000;
    private static final int TRADING_DAYS_PER_MONTH = 21;
    private static final double TRADING_DAYS_PER_YEAR = 252.0;

    public ProjectionResult project(String symbol, BigDecimal currentValue, int months) {
        int cappedMonths = Math.min(months, 120);
        String sym = symbol.toUpperCase();

        LocalDate since = LocalDate.now().minusYears(3);
        Map<String, NavigableMap<LocalDate, Double>> seriesMap =
                returnSeriesService.getReturnSeries(List.of(sym), since);
        NavigableMap<LocalDate, Double> returns = seriesMap.get(sym);

        var bandResult = scenarioBandService.project(sym, currentValue, cappedMonths);

        if (returns == null || returns.size() < ReturnSeriesService.MIN_OBSERVATIONS) {
            return new ProjectionResult(bandResult.bands(), List.of());
        }

        double[] retArray = returns.values().stream().mapToDouble(Double::doubleValue).toArray();
        double mu = shrunkDailyDrift(retArray);

        int totalDays = cappedMonths * TRADING_DAYS_PER_MONTH;
        double[] dailySigma = dailySigmaSchedule(garchService.fit(retArray), totalDays);
        Random rng = new Random(0);

        double[][] paths = new double[MC_PATHS][totalDays];
        for (int path = 0; path < MC_PATHS; path++) {
            double logVal = 0.0;
            for (int day = 0; day < totalDays; day++) {
                double sigma = dailySigma[day];
                logVal += (mu - 0.5 * sigma * sigma) + sigma * rng.nextGaussian();
                paths[path][day] = logVal;
            }
        }

        List<MonteCarloInterval> mcIntervals = new ArrayList<>();
        double cv = currentValue.doubleValue();
        LocalDate cursor = LocalDate.now();

        for (int m = 0; m < cappedMonths; m++) {
            cursor = cursor.plusMonths(1);
            int dayIdx = (m + 1) * TRADING_DAYS_PER_MONTH - 1;
            double[] vals = new double[MC_PATHS];
            for (int p = 0; p < MC_PATHS; p++) {
                vals[p] = cv * Math.exp(paths[p][dayIdx]);
            }
            Arrays.sort(vals);
            mcIntervals.add(new MonteCarloInterval(
                    cursor,
                    BigDecimal.valueOf(percentile(vals, 5)).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(percentile(vals, 50)).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(percentile(vals, 95)).setScale(2, RoundingMode.HALF_UP)
            ));
        }

        return new ProjectionResult(bandResult.bands(), mcIntervals);
    }

    /**
     * Shrinks the trailing sample mean of daily returns toward a long-run CAPM-style prior
     * (risk-free rate + equity risk premium). A daily-return sample mean has a standard
     * error far larger than its own magnitude — even 3 years (~750 obs) of daily data barely
     * pins down the true long-run drift — so compounding it unshrunk over a decade-long
     * simulation would inherit that estimation noise directly into the projected bands.
     *
     * <p>{@code muShrunk = (T·muSample + K·muPrior) / (T + K)}, the same shrinkage shape
     * {@code ConfidenceCalibrationService} uses for confidence calibration.
     */
    double shrunkDailyDrift(double[] retArray) {
        double muSample = Arrays.stream(retArray).average().orElse(0.0);
        double muPrior = (riskProperties.getRiskFreeRate() + riskProperties.getEquityRiskPremium())
                / TRADING_DAYS_PER_YEAR;
        double t = retArray.length;
        double k = riskProperties.getDriftShrinkageDays();
        return (t * muSample + k * muPrior) / (t + k);
    }

    /**
     * Builds a per-day forward daily-vol schedule for the simulation horizon. When the GARCH
     * fit is mean-reverting, variance decays geometrically from the one-step forecast toward
     * the long-run level — the same recursion {@code GarchService} uses internally:
     * {@code σ²_{t+h} = σ²_LR + p^(h-1)·(σ²_{t+1} − σ²_LR)}. A flat one-step vol held for
     * every future day (the prior behavior) silently assumed today's conditional vol persists
     * unchanged for up to 10 years. The EWMA fallback models variance as a random walk with no
     * mean reversion, so a flat schedule at its one-step vol is the correct forecast there.
     */
    double[] dailySigmaSchedule(VolForecast vol, int totalDays) {
        double[] sigma = new double[totalDays];
        if (vol.isGarch() && !Double.isNaN(vol.longRunDailyVol())) {
            double sigma2Next = vol.conditionalDailyVol() * vol.conditionalDailyVol();
            double sigma2LR = vol.longRunDailyVol() * vol.longRunDailyVol();
            double persistence = vol.persistence();
            for (int day = 0; day < totalDays; day++) {
                double sigma2h = sigma2LR + Math.pow(persistence, day) * (sigma2Next - sigma2LR);
                sigma[day] = Math.sqrt(Math.max(sigma2h, 0.0));
            }
        } else {
            Arrays.fill(sigma, vol.conditionalDailyVol());
        }
        return sigma;
    }

    private double percentile(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
