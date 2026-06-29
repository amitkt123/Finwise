package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry.SignalStatus;
import org.amit.finwise.cfo.repository.macro.PolicyQuantSignalRepository;
import org.amit.finwise.cfo.service.analytics.StressScenarioService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Admin REST endpoints for inspecting / overriding the live {@link QuantitativeMacroState}
 * and reviewing policy-quant signals pending human confirmation.
 *
 * <pre>
 *   GET  /api/admin/macro-state
 *   GET  /api/admin/macro-state/audit
 *   GET  /api/admin/policy-signals?status=PENDING&amp;page=0
 *   POST /api/admin/policy-signals/{id}/confirm
 *   POST /api/admin/policy-signals/{id}/override   body: {"value": 0.065}
 *   POST /api/admin/policy-signals/{id}/reject     body: {"reason": "..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminMacroStateController {

    private final QuantitativeMacroState macroState;
    private final PolicyQuantSignalRepository signalRepo;
    private final StressScenarioService stressScenarioService;

    @GetMapping("/macro-state")
    public Map<String, Object> currentState() {
        return Map.of(
                "riskFreeRate", macroState.getRiskFreeRate(),
                "crisisProbability", macroState.getCrisisProbability(),
                "regimeVolCalm", macroState.getRegimeVolCalm(),
                "regimeVolCrisis", macroState.getRegimeVolCrisis(),
                "yieldCurve10y", macroState.getYieldCurve10y(),
                "yieldCurveSlope", macroState.getYieldCurveSlope(),
                "fiiFlowScore", macroState.getFiiFlowScore(),
                "policyRateShocks", macroState.getPolicyRateShocks()
        );
    }

    @GetMapping("/macro-state/audit")
    public Object auditLog() {
        return macroState.getAuditLog();
    }

    @GetMapping("/policy-signals")
    public Object listSignals(
            @RequestParam(defaultValue = "PENDING") SignalStatus status,
            @RequestParam(defaultValue = "0") int page) {
        return signalRepo.findByStatus(status, PageRequest.of(page, 20));
    }

    @PostMapping("/policy-signals/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable Long id) {
        return signalRepo.findById(id).map(entry -> {
            applySignal(entry.getParameterKey(), entry.getProposedValue(), "ADMIN");
            entry.setStatus(SignalStatus.CONFIRMED);
            entry.setResolvedAt(Instant.now());
            entry.setResolvedBy("ADMIN");
            signalRepo.save(entry);
            return ResponseEntity.ok(entry);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/policy-signals/{id}/override")
    public ResponseEntity<?> override(@PathVariable Long id,
                                      @RequestBody Map<String, Double> body) {
        return signalRepo.findById(id).map(entry -> {
            double val = body.get("value");
            applySignal(entry.getParameterKey(), val, "ADMIN");
            entry.setOverrideValue(val);
            entry.setStatus(SignalStatus.OVERRIDDEN);
            entry.setResolvedAt(Instant.now());
            entry.setResolvedBy("ADMIN");
            signalRepo.save(entry);
            return ResponseEntity.ok(entry);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/policy-signals/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id,
                                    @RequestBody(required = false) Map<String, String> body) {
        return signalRepo.findById(id).map(entry -> {
            entry.setStatus(SignalStatus.REJECTED);
            entry.setRejectReason(body != null ? body.get("reason") : null);
            entry.setResolvedAt(Instant.now());
            entry.setResolvedBy("ADMIN");
            signalRepo.save(entry);
            return ResponseEntity.ok(entry);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/policy-signals/transmission-table")
    public ResponseEntity<byte[]> downloadTransmissionTable() throws Exception {
        var resource = new ClassPathResource("data/policy_transmission.csv");
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=policy_transmission.csv")
                .body(resource.getContentAsByteArray());
    }

    @PostMapping("/policy-signals/transmission-table")
    public ResponseEntity<?> reloadTransmissionTable(@RequestBody byte[] csv) {
        try {
            stressScenarioService.reloadScenarios(new java.io.ByteArrayInputStream(csv));
            return ResponseEntity.ok(Map.of("status", "reloaded"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private void applySignal(String paramKey, double value, String actor) {
        if ("riskFreeRate".equals(paramKey)) {
            macroState.setRiskFreeRate(value, actor);
        } else {
            macroState.putPolicyRateShock(paramKey, value);
        }
    }
}
