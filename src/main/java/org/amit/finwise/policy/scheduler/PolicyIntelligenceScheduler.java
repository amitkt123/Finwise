package org.amit.finwise.policy.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.policy.service.PolicyDocumentCrawlerService;
import org.amit.finwise.policy.service.PolicyFalsificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyIntelligenceScheduler {

    private final PolicyDocumentCrawlerService policyDocumentCrawlerService;
    private final PolicyFalsificationService policyFalsificationService;

    @Value("${policy.sync.enabled:false}")
    private boolean syncEnabled;

    @Value("${policy.falsification.enabled:true}")
    private boolean falsificationEnabled;

    @Scheduled(cron = "${policy.sync.cron:0 15 8,14,20 * * MON-SAT}", zone = "Asia/Kolkata")
    public void syncPolicySources() {
        if (!syncEnabled) {
            return;
        }
        try {
            PolicyDocumentCrawlerService.SyncResult result = policyDocumentCrawlerService.syncAll();
            log.info("[PolicyCrawler] Scheduled sync complete: ingested={}, errors={}",
                    result.documentsIngested(), result.errors().size());
        } catch (Exception e) {
            log.warn("[PolicyCrawler] Scheduled sync failed: {}", e.getMessage());
        }
    }

    /** Phase 3.3 — nightly falsification check: did predicted reactions materialize? */
    @Scheduled(cron = "${policy.falsification.cron:0 30 22 * * *}", zone = "Asia/Kolkata")
    public void checkFalsification() {
        if (!falsificationEnabled) {
            return;
        }
        try {
            int checked = policyFalsificationService.checkPendingImpacts();
            log.info("[PolicyFalsification] Nightly check complete: {} impacts evaluated", checked);
        } catch (Exception e) {
            log.warn("[PolicyFalsification] Nightly check failed: {}", e.getMessage());
        }
    }
}
