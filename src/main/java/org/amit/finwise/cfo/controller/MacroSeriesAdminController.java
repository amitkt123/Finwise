package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational endpoints for the macro time-series layer (DF-3).
 *
 *   POST /api/cfo/macro-series/ingest/daily    — pull FBIL curve + USD/INR, RBI rates, VIX
 *   POST /api/cfo/macro-series/ingest/monthly  — pull MOSPI CPI/IIP/WPI history
 *   GET  /api/cfo/macro-series/freshness       — per-series latest obs + SLA status
 */
@RestController
@RequestMapping("/api/cfo/macro-series")
@RequiredArgsConstructor
public class MacroSeriesAdminController {

    private final MacroSeriesService macroSeriesService;

    @PostMapping("/ingest/daily")
    public Map<String, Object> ingestDaily() {
        MacroSeriesService.IngestResult r = macroSeriesService.ingestDaily();
        return result(r);
    }

    @PostMapping("/ingest/monthly")
    public Map<String, Object> ingestMonthly() {
        MacroSeriesService.IngestResult r = macroSeriesService.ingestMonthly();
        return result(r);
    }

    @GetMapping("/freshness")
    public Map<String, Object> freshness() {
        return macroSeriesService.freshnessView();
    }

    private Map<String, Object> result(MacroSeriesService.IngestResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("observations", r.observations());
        out.put("seriesTouched", r.seriesTouched());
        return out;
    }
}
