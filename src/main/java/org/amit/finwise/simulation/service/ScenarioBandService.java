package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.simulation.dto.ScenarioBand;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ScenarioBandService {

    private final ReturnSeriesService returnSeriesService;

    private static final int WINDOW = 252;

    public record ScenarioBandResult(List<ScenarioBand> bands, boolean sufficientHistory) {}

    public ScenarioBandResult project(String symbol, BigDecimal currentValue, int months) {
        LocalDate since = LocalDate.now().minusYears(3);
        Map<String, NavigableMap<LocalDate, Double>> seriesMap =
                returnSeriesService.getReturnSeries(List.of(symbol.toUpperCase()), since);

        NavigableMap<LocalDate, Double> returns = seriesMap.get(symbol.toUpperCase());
        if (returns == null || returns.size() < ReturnSeriesService.MIN_OBSERVATIONS) {
            return new ScenarioBandResult(List.of(), false);
        }

        double[] ret = returns.values().stream().mapToDouble(Double::doubleValue).toArray();
        int effectiveWindow = Math.min(WINDOW, ret.length);

        List<Double> rollingReturns = new ArrayList<>();
        for (int i = effectiveWindow; i <= ret.length; i++) {
            double compounded = 1.0;
            for (int j = i - effectiveWindow; j < i; j++) compounded *= (1.0 + ret[j]);
            rollingReturns.add(compounded - 1.0);
        }
        Collections.sort(rollingReturns);

        double p25 = percentile(rollingReturns, 25);
        double p50 = percentile(rollingReturns, 50);
        double p75 = percentile(rollingReturns, 75);

        double monthlyOpt = Math.pow(1 + p75, 1.0 / 12.0) - 1;
        double monthlyNeu = Math.pow(1 + p50, 1.0 / 12.0) - 1;
        double monthlyPes = Math.pow(1 + p25, 1.0 / 12.0) - 1;

        List<ScenarioBand> bands = new ArrayList<>();
        BigDecimal opt = currentValue, neu = currentValue, pes = currentValue;
        LocalDate cursor = LocalDate.now();
        for (int m = 1; m <= months; m++) {
            cursor = cursor.plusMonths(1);
            opt = opt.multiply(BigDecimal.valueOf(1 + monthlyOpt)).setScale(2, RoundingMode.HALF_UP);
            neu = neu.multiply(BigDecimal.valueOf(1 + monthlyNeu)).setScale(2, RoundingMode.HALF_UP);
            pes = pes.multiply(BigDecimal.valueOf(1 + monthlyPes)).setScale(2, RoundingMode.HALF_UP);
            bands.add(new ScenarioBand(cursor, opt, neu, pes));
        }

        return new ScenarioBandResult(bands, true);
    }

    private double percentile(List<Double> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
