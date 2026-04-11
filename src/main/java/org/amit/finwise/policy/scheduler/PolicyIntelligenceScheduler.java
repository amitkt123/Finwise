package org.amit.finwise.policy.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.policy.service.PolicyDocumentCrawlerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyIntelligenceScheduler {

    private final PolicyDocumentCrawlerService policyDocumentCrawlerService;

    @Value("${policy.sync.enabled:false}")
    private boolean syncEnabled;

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
}
