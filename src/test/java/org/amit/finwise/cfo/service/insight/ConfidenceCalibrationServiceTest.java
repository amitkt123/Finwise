package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.EventOutcome.Horizon;
import org.amit.finwise.cfo.service.InsightEvaluationService;
import org.amit.finwise.cfo.service.InsightEvaluationService.CalibrationRow;
import org.amit.finwise.cfo.service.insight.ConfidenceCalibrationService.Cohort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Phase C calibration: shrink a raw confidence toward the realised hit rate in proportion to
 * the evidence behind it, and render the cohort's track record.
 */
@ExtendWith(MockitoExtension.class)
class ConfidenceCalibrationServiceTest {

    @Mock InsightEvaluationService evaluationService;
    @InjectMocks ConfidenceCalibrationService service;

    private static CalibrationRow row(String provider, Horizon horizon,
                                      int n, int hits, double avgConfidence) {
        double hitRate = n == 0 ? 0 : (double) hits / n;
        return new CalibrationRow(provider, "v2", horizon, n, hits, hitRate, avgConfidence,
                0.0, 0, 0.0, 0.0);
    }

    // ── Acceptance: overconfidence is corrected ──────────────────────────────────

    @Test
    void overconfidentCohortPullsCalibratedBelowRaw() {
        // 55% realised hit rate, 70% stated confidence, ample evidence (n=40).
        Cohort cohort = new Cohort("claude", Horizon.H5D, 40, 22, 0.70);
        double calibrated = service.calibrate(0.70, cohort);

        // (40·0.55 + 10·0.70) / 50 = 0.58
        assertEquals(0.58, calibrated, 1e-9);
        assertTrue(calibrated < 0.70, "overconfidence must drag calibrated below raw");
    }

    // ── Acceptance: small-n is dominated by the raw prior ────────────────────────

    @Test
    void smallCohortLeavesCalibratedNearRaw() {
        // Only 2 scored calls: the prior (k=10) dominates, so calibrated ≈ raw.
        Cohort cohort = new Cohort("claude", Horizon.H5D, 2, 1, 0.80);
        double calibrated = service.calibrate(0.80, cohort);

        // (2·0.5 + 10·0.80) / 12 = 0.75 — far closer to raw (0.80) than to hitRate (0.50)
        assertEquals(0.75, calibrated, 1e-9);
        assertTrue(Math.abs(calibrated - 0.80) < Math.abs(calibrated - 0.50),
                "with little evidence the raw figure should dominate");
    }

    @Test
    void emptyCohortReturnsRawUnchangedAndNoTrackRecord() {
        Cohort empty = new Cohort(null, null, 0, 0, 0.0);
        assertEquals(0.73, service.calibrate(0.73, empty), 1e-9);
        assertNull(service.trackRecord(empty));
    }

    @Test
    void calibratedConfidenceIsClampedToUnitInterval() {
        Cohort cohort = new Cohort("claude", Horizon.H1D, 30, 30, 0.95); // 100% hit rate
        double calibrated = service.calibrate(1.2, cohort);             // out-of-range raw
        assertTrue(calibrated >= 0.0 && calibrated <= 1.0);
    }

    // ── Track-record rendering ───────────────────────────────────────────────────

    @Test
    void trackRecordRendersOverconfidenceGap() {
        Cohort cohort = new Cohort("claude", Horizon.H5D, 40, 22, 0.70);
        assertEquals("scoreboard: 55% hit @5d, n=40 (overconfident by 0.15)",
                service.trackRecord(cohort));
    }

    @Test
    void trackRecordRendersUnderconfidenceAndWellCalibrated() {
        assertEquals("scoreboard: 70% hit @20d, n=30 (underconfident by 0.20)",
                service.trackRecord(new Cohort("c", Horizon.H20D, 30, 21, 0.50)));
        assertEquals("scoreboard: 60% hit @1d, n=25 (well-calibrated)",
                service.trackRecord(new Cohort("c", Horizon.H1D, 25, 15, 0.62)));
    }

    @Test
    void trackRecordWithNullHorizonLabelsAllHorizons() {
        assertEquals("scoreboard: 50% hit all horizons, n=20 (well-calibrated)",
                service.trackRecord(new Cohort(null, null, 20, 10, 0.50)));
    }

    // ── Cohort folding / filtering ───────────────────────────────────────────────

    @Test
    void cohortFoldsAcrossPromptVersionsAndNWeightsConfidence() {
        List<CalibrationRow> rows = List.of(
                row("claude", Horizon.H5D, 10, 5, 0.60),   // hits 5
                row("claude", Horizon.H5D, 30, 21, 0.80),  // hits 21
                row("ollama", Horizon.H5D, 99, 99, 0.99),  // wrong provider
                row("claude", Horizon.H1D, 99, 99, 0.99)); // wrong horizon

        Cohort c = service.cohort(rows, "claude", Horizon.H5D);
        assertEquals(40, c.n());
        assertEquals(26, c.hits());
        assertEquals(0.65, c.hitRate(), 1e-9);
        // n-weighted: (10·0.60 + 30·0.80) / 40 = 0.75
        assertEquals(0.75, c.avgConfidence(), 1e-9);
    }

    @Test
    void cohortWithNullFiltersAggregatesEverything() {
        List<CalibrationRow> rows = List.of(
                row("claude", Horizon.H5D, 10, 5, 0.60),
                row("ollama", Horizon.H1D, 10, 9, 0.90));

        Cohort c = service.cohort(rows, null, null);
        assertEquals(20, c.n());
        assertEquals(14, c.hits());
    }

    // ── Public path wires the configured lookback into the scoreboard ────────────

    @Test
    void publicCalibrateUsesScoreboardCohort() {
        ReflectionTestUtils.setField(service, "lookbackDays", 90);
        when(evaluationService.calibrationReport(90))
                .thenReturn(List.of(row("claude", Horizon.H5D, 40, 22, 0.70)));

        assertEquals(0.58, service.calibrate(0.70, "claude", Horizon.H5D), 1e-9);
        assertEquals("scoreboard: 55% hit @5d, n=40 (overconfident by 0.15)",
                service.trackRecord("claude", Horizon.H5D));
    }
}
