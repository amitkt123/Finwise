package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.InsightEvaluationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * DF-7 — inspection + manual-trigger endpoints for the insight evaluation loop.
 * The calibration scoreboard is the gate on any prompt tuning: a provider whose
 * hit rate trails its self-reported confidence is over-confident, not skilled.
 */
@RestController
@RequestMapping("/api/cfo/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final InsightEvaluationService evaluationService;

    /**
     * Calibration scoreboard per (provider, prompt-version, horizon) over the
     * last {@code days} of brief claims (default 90).
     */
    @GetMapping("/calibration")
    public List<InsightEvaluationService.CalibrationRow> calibration(
            @RequestParam(value = "days", defaultValue = "90") int days) {
        return evaluationService.calibrationReport(days);
    }

    /** Force a scoring pass over matured claims without waiting for the nightly job. */
    @PostMapping("/score")
    public InsightEvaluationService.ScoreResult score() {
        return evaluationService.scoreMaturedClaims();
    }
}
