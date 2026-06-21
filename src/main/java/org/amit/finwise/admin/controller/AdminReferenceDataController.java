package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.StressScenarioService;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/reference")
@RequiredArgsConstructor
public class AdminReferenceDataController {

    private final SymbolExtractorService symbolExtractorService;
    private final StressScenarioService stressScenarioService;
    private final AttributionService attributionService;

    // ── Symbol Gazetteer ──────────────────────────────────────────────────────

    @GetMapping("/symbol-gazetteer")
    public ResponseEntity<byte[]> downloadGazetteer() {
        byte[] csv = symbolExtractorService.getRawGazetteerCsv();
        return csvResponse(csv, "symbol_gazetteer.csv");
    }

    @PostMapping("/symbol-gazetteer")
    public ResponseEntity<Map<String, String>> uploadGazetteer(@RequestParam("file") MultipartFile file)
            throws IOException {
        symbolExtractorService.reloadGazetteer(file.getInputStream());
        return ResponseEntity.ok(Map.of("status", "reloaded", "file", "symbol_gazetteer"));
    }

    // ── Stress Scenarios ──────────────────────────────────────────────────────

    @GetMapping("/stress-scenarios")
    public ResponseEntity<byte[]> downloadScenarios() {
        byte[] csv = stressScenarioService.getRawScenariosCsv();
        return csvResponse(csv, "stress_scenarios.csv");
    }

    @PostMapping("/stress-scenarios")
    public ResponseEntity<Map<String, String>> uploadScenarios(@RequestParam("file") MultipartFile file)
            throws IOException {
        stressScenarioService.reloadScenarios(file.getInputStream());
        return ResponseEntity.ok(Map.of("status", "reloaded", "file", "stress_scenarios"));
    }

    // ── Sector Weights ────────────────────────────────────────────────────────

    @GetMapping("/sector-weights")
    public ResponseEntity<byte[]> downloadSectorWeights() throws IOException {
        byte[] csv = attributionService.getRawWeightsCsv();
        return csvResponse(csv, "nifty_sector_weights.csv");
    }

    @PostMapping("/sector-weights")
    public ResponseEntity<Map<String, String>> uploadSectorWeights(@RequestParam("file") MultipartFile file)
            throws IOException {
        attributionService.reloadSectorWeights(file.getInputStream());
        return ResponseEntity.ok(Map.of("status", "reloaded", "file", "nifty_sector_weights"));
    }

    private ResponseEntity<byte[]> csvResponse(byte[] content, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .contentLength(content.length)
                .body(content);
    }
}
