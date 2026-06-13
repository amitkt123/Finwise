package org.amit.finwise.marketdata.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.CorporateEventRepository;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.amit.finwise.marketdata.repository.PriceAdjustmentRepository;
import org.amit.finwise.marketdata.service.CorporateActionService;
import org.amit.finwise.marketdata.service.CorporateEventService;
import org.amit.finwise.marketdata.service.EodIngestionService;
import org.amit.finwise.marketdata.service.MarketDataSeedService;
import org.amit.finwise.marketdata.service.PriceAdjustmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational endpoints for the market-data layer.
 *
 *   POST /api/marketdata/seed?from=2020-01-01[&to=...]  — start historical seed walk
 *   POST /api/marketdata/seed/stop                      — stop the walk (resumable)
 *   GET  /api/marketdata/seed/status                    — walk progress
 *   POST /api/marketdata/ingest?date=...                — force one date (debugging)
 *   GET  /api/marketdata/quality                        — coverage / freshness summary
 */
@RestController
@RequestMapping("/api/marketdata")
@RequiredArgsConstructor
public class MarketDataAdminController {

    private final MarketDataSeedService seedService;
    private final EodIngestionService ingestionService;
    private final org.amit.finwise.marketdata.service.SeedValidationService validationService;
    private final CorporateActionService corporateActionService;
    private final CorporateEventService corporateEventService;
    private final PriceAdjustmentService priceAdjustmentService;
    private final InstrumentRepository instrumentRepo;
    private final EodPriceRepository eodRepo;
    private final IngestionRunRepository runRepo;
    private final CorporateActionRepository corporateActionRepo;
    private final CorporateEventRepository corporateEventRepo;
    private final PriceAdjustmentRepository priceAdjustmentRepo;

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> startSeed(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (!seedService.start(from, end)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(seedService.status());
        }
        return ResponseEntity.accepted().body(seedService.status());
    }

    @PostMapping("/seed/stop")
    public Map<String, Object> stopSeed() {
        seedService.requestStop();
        return seedService.status();
    }

    @GetMapping("/seed/status")
    public Map<String, Object> seedStatus() {
        return seedService.status();
    }

    @PostMapping("/ingest")
    public Map<String, Object> ingestDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        EodIngestionService.IngestResult bhav = ingestionService.ingestBhavcopy(date, true);
        EodIngestionService.IngestResult idx = ingestionService.ingestIndexCloseAll(date, true);
        EodIngestionService.IngestResult deliv = ingestionService.ingestDelivery(date, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date);
        result.put("bhavcopy", Map.of("status", bhav.status(), "rows", bhav.rows()));
        result.put("indexClose", Map.of("status", idx.status(), "rows", idx.rows()));
        result.put("delivery", Map.of("status", deliv.status(), "rows", deliv.rows()));
        return result;
    }

    @GetMapping("/validate")
    public Map<String, Object> validate() {
        return validationService.validate();
    }

    // ── Corporate actions ───────────────────────────────────────────────────

    @PostMapping("/corporate-actions/seed")
    public ResponseEntity<Map<String, Object>> seedCorporateActions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(ZoneId.of("Asia/Kolkata"));
        if (!corporateActionService.startSeed(from, end)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(corporateActionService.seedStatusView());
        }
        return ResponseEntity.accepted().body(corporateActionService.seedStatusView());
    }

    @PostMapping("/corporate-actions/seed/stop")
    public Map<String, Object> stopCorporateActionsSeed() {
        corporateActionService.requestStop();
        return corporateActionService.seedStatusView();
    }

    @GetMapping("/corporate-actions/seed/status")
    public Map<String, Object> corporateActionsSeedStatus() {
        return corporateActionService.seedStatusView();
    }

    @PostMapping("/corporate-actions/ingest")
    public Map<String, Object> ingestCorporateActions(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CorporateActionService.IngestResult r = corporateActionService.ingestRange(from, to, true);
        return Map.of("from", from, "to", to, "status", r.status(), "rows", r.rows());
    }

    @PostMapping("/adjustments/recompute")
    public Map<String, Object> recomputeAdjustments() {
        int rows = priceAdjustmentService.recomputeAll();
        return Map.of("rowsTouched", rows, "adjustments", priceAdjustmentRepo.count());
    }

    // ── Event calendar ──────────────────────────────────────────────────────

    @PostMapping("/events/calendar")
    public Map<String, Object> ingestEventCalendar() {
        CorporateEventService.ImportResult r = corporateEventService.ingestEventCalendar();
        return Map.of("status", r.status(), "rows", r.rows(), "message", r.message());
    }

    @PostMapping("/events/import-historical")
    public Map<String, Object> importHistoricalEvents(@RequestParam String path) {
        CorporateEventService.ImportResult r =
                corporateEventService.importEarningsHistory(Path.of(path));
        return Map.of("status", r.status(), "rows", r.rows(), "message", r.message());
    }

    @GetMapping("/quality")
    public Map<String, Object> quality() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("instruments", instrumentRepo.count());
        q.put("eodRows", eodRepo.count());
        q.put("eodMinDate", eodRepo.findMinTradeDate().orElse(null));
        q.put("eodMaxDate", eodRepo.findMaxTradeDate().orElse(null));
        q.put("corporateActions", corporateActionRepo.count());
        q.put("priceAdjustments", priceAdjustmentRepo.count());
        q.put("corporateEvents", corporateEventRepo.count());
        for (String job : new String[]{IngestionRun.JOB_BHAVCOPY, IngestionRun.JOB_INDEX_CLOSE,
                IngestionRun.JOB_DELIVERY, IngestionRun.JOB_CORPORATE_ACTION,
                IngestionRun.JOB_EVENT_CALENDAR}) {
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (IngestionRun.Status s : IngestionRun.Status.values()) {
                byStatus.put(s.name(), runRepo.countByJobNameAndStatus(job, s));
            }
            q.put(job, byStatus);
        }
        return q;
    }
}
