package org.amit.finwise.marketdata.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Ops dashboard endpoints (DF-5).
 *
 *   GET  /api/data-quality          — per-dataset freshness, counts, gaps, macro SLA
 *   POST /api/data-quality/repair   — run gap repair now (returns the repair report)
 *   POST /api/data-quality/backup   — trigger an on-demand pg_dump
 */
@RestController
@RequestMapping("/api/data-quality")
@RequiredArgsConstructor
public class DataQualityController {

    private final DataQualityService dataQualityService;
    private final GapRepairService gapRepairService;
    private final PgBackupService pgBackupService;

    @GetMapping
    public Map<String, Object> dataQuality() {
        return dataQualityService.report();
    }

    @PostMapping("/repair")
    public GapRepairService.RepairReport repair() {
        return gapRepairService.repairGaps();
    }

    @PostMapping("/backup")
    public Map<String, Object> backup() {
        return pgBackupService.dumpNow();
    }
}
