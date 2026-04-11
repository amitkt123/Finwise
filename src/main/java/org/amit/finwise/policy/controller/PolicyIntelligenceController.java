package org.amit.finwise.policy.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.policy.model.*;
import org.amit.finwise.policy.service.PolicyDocumentCrawlerService;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policy-intelligence")
@RequiredArgsConstructor
public class PolicyIntelligenceController {

    private final PolicyIntelligenceService policyIntelligenceService;
    private final PolicyDocumentCrawlerService policyDocumentCrawlerService;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    @PostMapping("/documents/ingest/text")
    public ResponseEntity<PolicyIntelligenceService.PolicyDocumentSummary> ingestText(
            @RequestBody PolicyIngestionPayload payload) {
        var document = policyIntelligenceService.ingestDocument(payload.toRequest());
        return policyIntelligenceService.getDocumentSummary(
                        document.getDocumentKey())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.internalServerError().build());
    }

    @GetMapping("/documents")
    public ResponseEntity<List<PolicyIntelligenceService.PolicyDocumentSummary>> listDocuments(
            @RequestParam(required = false) PolicyAuthority authority,
            @RequestParam(required = false) PolicyDocumentStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(
                policyIntelligenceService.listRecentDocuments(authority, status, limit));
    }

    @GetMapping("/search")
    public ResponseEntity<PolicyIntelligenceService.PolicySearchResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(policyIntelligenceService.search(query, limit));
    }

    @GetMapping("/context")
    public ResponseEntity<PolicyIntelligenceService.AdvisorPolicyContext> getAdvisorContext(
            @RequestParam(required = false) String message,
            @RequestParam(defaultValue = "6") int limit) {
        return ResponseEntity.ok(
                policyIntelligenceService.buildAdvisorContext(defaultUserId, message, limit));
    }

    @PostMapping("/sync")
    public ResponseEntity<PolicyDocumentCrawlerService.SyncResult> syncAllSources() {
        return ResponseEntity.ok(policyDocumentCrawlerService.syncAll());
    }

    @PostMapping("/sync/{sourceKey}")
    public ResponseEntity<PolicyDocumentCrawlerService.SyncResult> syncSource(
            @PathVariable String sourceKey) {
        return ResponseEntity.ok(policyDocumentCrawlerService.syncSource(sourceKey));
    }
}
