package org.amit.finwise.cfo.service.analytics;

import org.springframework.stereotype.Service;

@Service
public class KalmanBetaService {

    private static final double Q_BASE = 1e-4;
    private static final double DRIFT_LOOKBACK = 60;

    public record KalmanResult(double[] currentBeta, double betaDrift, double[][] betaHistory) {}

    public KalmanResult fit(double[] assetReturns, double[][] factorReturns, double crisisProbability) {
        int T = assetReturns.length;
        int k = factorReturns.length;
        if (T < 20 || k == 0) return new KalmanResult(new double[k], 0.0, new double[0][]);

        double qEff = Q_BASE * (1 + crisisProbability * 5);
        double R = estimateResidualVar(assetReturns, factorReturns);
        double[] beta = new double[k];
        double[][] P = identity(k, qEff * 10);
        double[][] betaHistory = new double[T][k];

        for (int t = 0; t < T; t++) {
            // predict step: P += Q*I
            for (int i = 0; i < k; i++) P[i][i] += qEff;

            double[] x = new double[k];
            for (int j = 0; j < k; j++) x[j] = factorReturns[j][t];

            double innov = assetReturns[t] - dot(x, beta);
            double S = quadForm(x, P) + R;

            double[] K = new double[k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) K[i] += P[i][j] * x[j];
                K[i] /= S;
            }

            for (int i = 0; i < k; i++) beta[i] += K[i] * innov;

            // Full matrix update: P = (I - K x^T) P
            double[][] IminusKx = identity(k, 1.0);
            for (int i = 0; i < k; i++)
                for (int j = 0; j < k; j++) IminusKx[i][j] -= K[i] * x[j];
            P = matMul(IminusKx, P);

            betaHistory[t] = beta.clone();
        }

        double betaDrift = T > DRIFT_LOOKBACK
                ? beta[0] - betaHistory[T - (int) DRIFT_LOOKBACK - 1][0]
                : 0.0;
        return new KalmanResult(beta.clone(), betaDrift, betaHistory);
    }

    // Estimate R from OLS residuals so the Kalman gain is properly scaled.
    // Raw asset variance overestimates R for high-beta assets, killing convergence.
    private double estimateResidualVar(double[] assetReturns, double[][] factorReturns) {
        int T = assetReturns.length;
        int k = factorReturns.length;
        if (k == 1) {
            double[] f = factorReturns[0];
            double sumXY = 0, sumX2 = 0;
            for (int t = 0; t < T; t++) { sumXY += f[t] * assetReturns[t]; sumX2 += f[t] * f[t]; }
            double olsBeta = sumX2 == 0 ? 0 : sumXY / sumX2;
            double var = 0;
            for (int t = 0; t < T; t++) { double r = assetReturns[t] - olsBeta * f[t]; var += r * r; }
            return var / T + 1e-8;
        }
        // Multi-factor: fall back to raw asset variance
        double mean = 0;
        for (double r : assetReturns) mean += r;
        mean /= T;
        double var = 0;
        for (double r : assetReturns) var += (r - mean) * (r - mean);
        return var / T + 1e-8;
    }

    private double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private double quadForm(double[] x, double[][] P) {
        int k = x.length;
        double s = 0;
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++) s += x[i] * P[i][j] * x[j];
        return s;
    }

    private double[][] identity(int k, double scale) {
        double[][] I = new double[k][k];
        for (int i = 0; i < k; i++) I[i][i] = scale;
        return I;
    }

    private double[][] matMul(double[][] A, double[][] B) {
        int k = A.length;
        double[][] C = new double[k][k];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++)
                for (int l = 0; l < k; l++) C[i][j] += A[i][l] * B[l][j];
        return C;
    }
}
