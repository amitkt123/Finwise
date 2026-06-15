package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.service.InsightEvaluationService;
import org.amit.finwise.cfo.service.InsightEvaluationService.CalibrationRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Honest-Insights Phase C — calibrated confidence.
 *
 * <p>Replaces a self-reported {@code Confidence: 0.X} with a value anchored to the actual
 * track record, and renders that track record so the user can see why. The source of truth
 * is {@link InsightEvaluationService#calibrationReport(int)}: per (provider, prompt-version,
 * horizon) cohorts carrying {@code n}, {@code hitRate} and {@code avgConfidence}.
 *
 * <p><b>Shrinkage.</b> A raw confidence is pulled toward the cohort's realised hit rate in
 * proportion to how much evidence the cohort carries:
 * <pre>calibrated = (n·hitRate + k·rawConfidence) / (n + k)</pre>
 * With {@code k ≈ 10} a cohort needs ~10 scored calls before the data outweighs the raw guess.
 * A small cohort therefore yields {@code calibrated ≈ raw} (we have no reason to override the
 * stated figure yet); a large, over-confident cohort ({@code hitRate < avgConfidence}) drags
 * the figure down toward the truth.
 *
 * <p>All lookups degrade safely: an empty or failed scoreboard leaves the raw confidence
 * untouched and returns a null track record, so brief generation never breaks on this path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfidenceCalibrationService {

    /** Pseudo-count: how many raw-confidence "votes" the prior is worth against real outcomes. */
    private static final double SHRINKAGE_K = 10.0;

    private final InsightEvaluationService evaluationService;

    /** How far back the scoreboard is aggregated; calls older than this no longer inform calibration. */
    @Value("${cfo.calibration.lookback-days:180}")
    private int lookbackDays;

    /**
     * Shrink a raw confidence toward the realised hit rate of its cohort. {@code provider}
     * and {@code horizon} may be null to widen the cohort (null = aggregate across all
     * providers / all horizons). Returns the raw value unchanged when the cohort has no
     * scored calls yet.
     */
    public double calibrate(double rawConfidence, String provider, EventOutcome.Horizon horizon) {
        return calibrate(rawConfidence, cohort(report(), provider, horizon));
    }

    /**
     * Human-readable scoreboard line for a cohort, e.g.
     * {@code "scoreboard: 55% hit @5d, n=40 (overconfident by 0.15)"}. Returns null when the
     * cohort has no scored calls, so the renderer simply omits the line.
     */
    public String trackRecord(String provider, EventOutcome.Horizon horizon) {
        return trackRecord(cohort(report(), provider, horizon));
    }

    // ── Pure core (package-private, no I/O — directly unit-testable) ──────────────

    /** A (provider, horizon) cohort folded across prompt-versions: total n, hits, weighted confidence. */
    record Cohort(String provider, EventOutcome.Horizon horizon, int n, int hits, double avgConfidence) {
        double hitRate() { return n == 0 ? 0.0 : (double) hits / n; }
    }

    double calibrate(double rawConfidence, Cohort cohort) {
        double raw = clamp01(rawConfidence);
        if (cohort == null || cohort.n() == 0) return raw;            // no evidence → trust the raw figure
        double calibrated = (cohort.n() * cohort.hitRate() + SHRINKAGE_K * raw) / (cohort.n() + SHRINKAGE_K);
        return clamp01(calibrated);
    }

    String trackRecord(Cohort cohort) {
        if (cohort == null || cohort.n() == 0) return null;
        double gap = cohort.avgConfidence() - cohort.hitRate(); // + = stated higher than realised
        String calibration;
        if (Math.abs(gap) < 0.05) {
            calibration = "well-calibrated";
        } else if (gap > 0) {
            calibration = String.format("overconfident by %.2f", gap);
        } else {
            calibration = String.format("underconfident by %.2f", -gap);
        }
        String horizonLabel = cohort.horizon() == null
                ? "all horizons" : "@" + cohort.horizon().tradingDays + "d";
        return String.format("scoreboard: %.0f%% hit %s, n=%d (%s)",
                cohort.hitRate() * 100, horizonLabel, cohort.n(), calibration);
    }

    /**
     * Fold the scoreboard rows matching {@code provider}/{@code horizon} (null = wildcard) into a
     * single cohort, summing n and hits across prompt-versions and n-weighting the confidence.
     */
    Cohort cohort(List<CalibrationRow> rows, String provider, EventOutcome.Horizon horizon) {
        int n = 0, hits = 0;
        double confWeighted = 0.0;
        if (rows != null) {
            for (CalibrationRow r : rows) {
                if (provider != null && !provider.equalsIgnoreCase(r.provider())) continue;
                if (horizon != null && r.horizon() != horizon) continue;
                n += r.n();
                hits += r.hits();
                confWeighted += r.avgConfidence() * r.n();
            }
        }
        double avgConfidence = n == 0 ? 0.0 : confWeighted / n;
        return new Cohort(provider, horizon, n, hits, avgConfidence);
    }

    /** The scoreboard at the configured lookback, never throwing into brief generation. */
    List<CalibrationRow> report() {
        try {
            List<CalibrationRow> rows = evaluationService.calibrationReport(lookbackDays);
            return rows == null ? List.of() : rows;
        } catch (RuntimeException e) {
            log.warn("[Calibration] scoreboard unavailable, using raw confidence: {}", e.getMessage());
            return List.of();
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
