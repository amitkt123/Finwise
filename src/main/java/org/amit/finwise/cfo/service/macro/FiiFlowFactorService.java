package org.amit.finwise.cfo.service.macro;

import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class FiiFlowFactorService {

    public double[] zScore20d(double[] raw) {
        double[] out = new double[raw.length];
        for (int t = 0; t < raw.length; t++) {
            int start = Math.max(0, t - 19);
            double[] window = Arrays.copyOfRange(raw, start, t + 1);
            double mean = Arrays.stream(window).average().orElse(0);
            double std = Math.sqrt(Arrays.stream(window).map(v -> (v - mean) * (v - mean)).average().orElse(1));
            out[t] = std < 1e-10 ? 0 : (raw[t] - mean) / std;
        }
        return out;
    }

    public double[] orthogonalize(double[] fiiZ, double[] mkt) {
        int n = Math.min(fiiZ.length, mkt.length);
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += mkt[i];
            sumY += fiiZ[i];
            sumXY += mkt[i] * fiiZ[i];
            sumX2 += mkt[i] * mkt[i];
        }
        double denom = n * sumX2 - sumX * sumX;
        double beta = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
        double alpha = (sumY - beta * sumX) / n;
        double[] resid = new double[n];
        for (int i = 0; i < n; i++) resid[i] = fiiZ[i] - (alpha + beta * mkt[i]);
        return resid;
    }
}
