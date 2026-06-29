package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.simulation.dto.FactorAttribution;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FactorAttributionEngine {

    private static final String NIFTY = "^NSEI";
    private final ReturnSeriesService returnSeriesService;

    public FactorAttribution attribute(String symbol, LocalDate from) {
        String sym = symbol.toUpperCase();
        Map<String, NavigableMap<LocalDate, Double>> series =
                returnSeriesService.getReturnSeries(List.of(sym, NIFTY), from);

        NavigableMap<LocalDate, Double> stockRets = series.get(sym);
        NavigableMap<LocalDate, Double> mktRets   = series.get(NIFTY);

        if (stockRets == null || mktRets == null || stockRets.isEmpty()) {
            return new FactorAttribution(0.0, 0.0, 0.0, 0.0);
        }

        Set<LocalDate> common = new TreeSet<>(stockRets.keySet());
        common.retainAll(mktRets.keySet());
        if (common.size() < 2) {
            return new FactorAttribution(0.0, 0.0, 0.0, 0.0);
        }

        double[] y = new double[common.size()];
        double[] x = new double[common.size()];
        int i = 0;
        for (LocalDate date : common) {
            y[i] = stockRets.get(date);
            x[i] = mktRets.get(date);
            i++;
        }

        // OLS: beta = cov(x,y) / var(x),  alpha = mean(y) - beta * mean(x)
        double meanX = mean(x), meanY = mean(y);
        double cov = 0, varX = 0;
        for (int j = 0; j < x.length; j++) {
            cov  += (x[j] - meanX) * (y[j] - meanY);
            varX += (x[j] - meanX) * (x[j] - meanX);
        }
        double beta  = varX == 0 ? 0 : cov / varX;
        double alpha = meanY - beta * meanX;

        double totalReturn = round(compounded(y) * 100);
        double betaContr   = round(beta * compounded(x) * 100);
        double alphaContr  = round(alpha * y.length * 100);
        // unexplained is the exact remainder so components always sum to totalReturn
        double unexplained = round(totalReturn - betaContr - alphaContr);

        return new FactorAttribution(totalReturn, betaContr, alphaContr, unexplained);
    }

    private double mean(double[] arr) {
        double s = 0;
        for (double v : arr) s += v;
        return s / arr.length;
    }

    private double compounded(double[] arr) {
        double c = 1.0;
        for (double v : arr) c *= (1.0 + v);
        return c - 1.0;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
