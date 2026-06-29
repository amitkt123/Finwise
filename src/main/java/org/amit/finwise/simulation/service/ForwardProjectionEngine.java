package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
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

    static final int MC_PATHS = 1_000;
    private static final double SQRT_252 = Math.sqrt(252.0);
    private static final int TRADING_DAYS_PER_MONTH = 21;

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
        double mu = Arrays.stream(retArray).average().orElse(0.0);

        double dailySigma = garchService.fit(retArray).annualizedVol() / SQRT_252;

        int totalDays = cappedMonths * TRADING_DAYS_PER_MONTH;
        Random rng = new Random(0);

        double[][] paths = new double[MC_PATHS][totalDays];
        for (int path = 0; path < MC_PATHS; path++) {
            double logVal = 0.0;
            for (int day = 0; day < totalDays; day++) {
                logVal += (mu - 0.5 * dailySigma * dailySigma) + dailySigma * rng.nextGaussian();
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

    private double percentile(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
