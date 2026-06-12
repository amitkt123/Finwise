package org.amit.finwise.cfo.service.analytics;

/**
 * Ledoit-Wolf shrinkage of the sample covariance matrix toward the
 * constant-correlation target.
 *
 * Reference: Ledoit &amp; Wolf (2003), "Honey, I Shrunk the Sample Covariance
 * Matrix". The constant-correlation target is preferred over the identity
 * target here because an all-equity portfolio has a materially nonzero average
 * pairwise correlation.
 *
 * With T observations and N assets the sample matrix has N(N+1)/2 free
 * parameters; below T ≈ 5N it is badly conditioned and w'Σw is biased low.
 * Shrinkage pulls the off-diagonals toward the average correlation with a
 * data-driven intensity δ ∈ [0,1] that grows as estimation noise dominates.
 *
 * Internals follow the paper's 1/T moment convention; the returned matrix is
 * rescaled by T/(T−1) so it is interchangeable with the unbiased sample
 * covariance used elsewhere in the risk engine (δ→0 reproduces it exactly).
 */
public final class LedoitWolfShrinkage {

    private LedoitWolfShrinkage() {}

    /**
     * @param sigma shrunk covariance matrix, N-1 (unbiased) scale
     * @param delta shrinkage intensity δ ∈ [0,1]; 0 = pure sample, 1 = pure target
     */
    public record Result(double[][] sigma, double delta) {}

    /**
     * @param returns data[t][i] = return of asset i at time t (aligned, no gaps)
     */
    public static Result shrink(double[][] returns) {
        int T = returns.length;
        int N = T > 0 ? returns[0].length : 0;
        if (T < 2 || N < 2) {
            throw new IllegalArgumentException("need T ≥ 2 and N ≥ 2, got T=" + T + ", N=" + N);
        }

        // Demean each asset's return column
        double[][] x = new double[T][N];
        for (int i = 0; i < N; i++) {
            double mean = 0;
            for (double[] aReturn : returns) mean += aReturn[i];
            mean /= T;
            for (int t = 0; t < T; t++) x[t][i] = returns[t][i] - mean;
        }

        // Sample covariance with the paper's 1/T convention
        double[][] s = new double[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = i; j < N; j++) {
                double sum = 0;
                for (int t = 0; t < T; t++) sum += x[t][i] * x[t][j];
                s[i][j] = sum / T;
                s[j][i] = s[i][j];
            }
        }

        // Average sample correlation r̄ across the N(N−1)/2 pairs
        double rbar = 0;
        int pairs = 0;
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                rbar += s[i][j] / Math.sqrt(s[i][i] * s[j][j]);
                pairs++;
            }
        }
        rbar /= pairs;

        // Constant-correlation target F
        double[][] f = new double[N][N];
        for (int i = 0; i < N; i++) {
            f[i][i] = s[i][i];
            for (int j = i + 1; j < N; j++) {
                f[i][j] = rbar * Math.sqrt(s[i][i] * s[j][j]);
                f[j][i] = f[i][j];
            }
        }

        // π̂ = Σ_ij (1/T) Σ_t (x_it x_jt − s_ij)²  — asymptotic variance of the sample entries
        double piHat = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                double sum = 0;
                for (int t = 0; t < T; t++) {
                    double d = x[t][i] * x[t][j] - s[i][j];
                    sum += d * d;
                }
                piHat += sum / T;
            }
        }

        // ρ̂ = Σ_i π_ii + Σ_{i≠j} (r̄/2)(√(s_jj/s_ii)·θ_ii,ij + √(s_ii/s_jj)·θ_jj,ij)
        // with θ_kk,ij = (1/T) Σ_t (x_kt² − s_kk)(x_it x_jt − s_ij)
        double rhoHat = 0;
        for (int i = 0; i < N; i++) {
            double sum = 0;
            for (int t = 0; t < T; t++) {
                double d = x[t][i] * x[t][i] - s[i][i];
                sum += d * d;
            }
            rhoHat += sum / T;
        }
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                if (i == j) continue;
                double thetaII = 0;
                double thetaJJ = 0;
                for (int t = 0; t < T; t++) {
                    double dij = x[t][i] * x[t][j] - s[i][j];
                    thetaII += (x[t][i] * x[t][i] - s[i][i]) * dij;
                    thetaJJ += (x[t][j] * x[t][j] - s[j][j]) * dij;
                }
                thetaII /= T;
                thetaJJ /= T;
                rhoHat += (rbar / 2.0) * (Math.sqrt(s[j][j] / s[i][i]) * thetaII
                                        + Math.sqrt(s[i][i] / s[j][j]) * thetaJJ);
            }
        }

        // γ̂ = Σ_ij (f_ij − s_ij)²  — misspecification of the target
        double gammaHat = 0;
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                double d = f[i][j] - s[i][j];
                gammaHat += d * d;
            }
        }

        // Optimal intensity δ* = κ̂/T clamped to [0,1]; γ̂=0 means target == sample
        double kappa = gammaHat > 0 ? (piHat - rhoHat) / gammaHat : 0;
        double delta = Math.clamp(kappa / T, 0.0, 1.0);

        // Σ_LW = δF + (1−δ)S, rescaled to the unbiased (N−1) convention
        double rescale = T / (T - 1.0);
        double[][] sigma = new double[N][N];
        for (int i = 0; i < N; i++) {
            for (int j = 0; j < N; j++) {
                sigma[i][j] = (delta * f[i][j] + (1 - delta) * s[i][j]) * rescale;
            }
        }
        return new Result(sigma, delta);
    }
}
