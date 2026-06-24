package org.amit.finwise.policy.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.policy.model.*;
import org.amit.finwise.policy.service.PolicyDocumentCrawlerService;
import org.amit.finwise.policy.service.PolicyEmbeddingService;
import org.amit.finwise.policy.service.PolicyFalsificationService;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.amit.finwise.policy.service.PolicyTimelineService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policy-intelligence")
@RequiredArgsConstructor
public class PolicyIntelligenceController {

    private final PolicyIntelligenceService policyIntelligenceService;
    private final PolicyDocumentCrawlerService policyDocumentCrawlerService;
    private final PolicyEmbeddingService policyEmbeddingService;
    private final PolicyTimelineService policyTimelineService;
    private final PolicyFalsificationService policyFalsificationService;

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
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) String message,
            @RequestParam(defaultValue = "6") int limit) {
        return ResponseEntity.ok(
                policyIntelligenceService.buildAdvisorContext(principal.getUsername(), message, limit));
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

    /** Phase 2.1 — backfill vector embeddings for existing policy chunks. */
    @PostMapping("/embeddings/backfill")
    public ResponseEntity<Integer> backfillEmbeddings(
            @RequestParam(defaultValue = "500") int limit) {
        return ResponseEntity.ok(policyEmbeddingService.backfillMissing(limit));
    }

    /** Phase 3.1 — evolution timeline for a policy subject (e.g. "repo-rate", "capital-gains"). */
    @GetMapping("/timeline/{subjectKey}")
    public ResponseEntity<PolicyTimelineService.PolicyTimeline> getTimeline(
            @PathVariable String subjectKey) {
        return ResponseEntity.ok(policyTimelineService.getTimeline(subjectKey));
    }

    /** Phase 3.1 — list all subject keys that have change records. */
    @GetMapping("/timeline")
    public ResponseEntity<java.util.List<String>> listTimelineSubjects() {
        return ResponseEntity.ok(policyTimelineService.listTrackedSubjects());
    }

    /** Phase 3.3 — trigger falsification check manually (admin). */
    @PostMapping("/falsification/check")
    public ResponseEntity<Integer> triggerFalsificationCheck() {
        return ResponseEntity.ok(policyFalsificationService.checkPendingImpacts());
    }
}
