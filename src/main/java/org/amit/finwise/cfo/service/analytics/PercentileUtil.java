package org.amit.finwise.cfo.service.analytics;

/**
 * Shared percentile-rank helper (Hazen convention, plotting position (i−0.5)/n).
 * Used for cross-sectional peer-valuation ranks and self-accumulating IV ranks.
 */
public final class PercentileUtil {

    private PercentileUtil() {}

    /**
     * Hazen percentile rank (0–100) of {@code value} within {@code sample}.
     * Ties take the average rank. The sample is expected to contain the value
     * itself for self-ranking use cases, but this is not required.
     */
    public static double hazenPercentile(double value, double[] sample) {
        int n = sample.length;
        if (n == 0) return Double.NaN;
        int below = 0;
        int equal = 0;
        for (double s : sample) {
            if (s < value) below++;
            else if (s == value) equal++;
        }
        // 1-based average rank of the value among the sample
        double rank = below + (equal > 0 ? (equal + 1) / 2.0 : 0.5);
        return (rank - 0.5) / n * 100.0;
    }
}
