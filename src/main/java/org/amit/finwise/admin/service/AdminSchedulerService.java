package org.amit.finwise.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.scheduler.CFOScheduler;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.policy.scheduler.PolicyIntelligenceScheduler;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSchedulerService {

    private final CFOScheduler cfoScheduler;
    private final PolicyIntelligenceScheduler policyIntelligenceScheduler;
    private final IngestionRunRepository ingestionRunRepository;

    public record SchedulerJobStatus(
            String name,
            String description,
            String cronExpression,
            LocalDateTime lastRunAt,
            boolean enabled
    ) {}

    private static final List<JobSpec> JOB_REGISTRY = List.of(
            new JobSpec("premarket-news",       "Fetch pre-market news & refine",            "0 0 7 * * MON-FRI",        null),
            new JobSpec("groww-sync-premarket",  "Sync Groww holdings pre-market",            "0 15 7 * * MON-FRI",       null),
            new JobSpec("morning-brief",         "Generate & email morning brief",             "0 30 7 * * MON-FRI",       null),
            new JobSpec("intraday-news",         "Intraday news refresh (every 30 min)",      "0 0/30 9-15 * * MON-FRI",  null),
            new JobSpec("price-fetch",           "Fetch stock prices & benchmark",             "0 0 16 * * MON-FRI",       null),
            new JobSpec("postmarket-news",       "Fetch post-market news",                    "0 15 16 * * MON-FRI",      null),
            new JobSpec("post-close-insight",    "Generate post-close market insight",        "0 30 16 * * MON-FRI",      null),
            new JobSpec("after-hours-digest",    "Generate & email after-hours digest",       "0 0 18 * * MON-FRI",       null),
            new JobSpec("macro-daily",           "Refresh daily macro snapshot",              "0 0 17 * * MON-FRI",       null),
            new JobSpec("macro-monthly",         "Refresh monthly MOSPI macro series",        "0 0 18 15 * *",            null),
            new JobSpec("fii-dii-flows",         "Fetch FII/DII institutional flows",         "0 0 19 * * MON-FRI",       null),
            new JobSpec("weekly-goal-review",    "Weekly goal review & email",                "0 0 9 * * SUN",            null),
            new JobSpec("weekly-peer-refresh",   "Weekly peer valuation universe refresh",    "0 0 10 * * SUN",           null),
            new JobSpec("rag-outcome-enrich",    "Enrich news event outcomes (RAG)",          "0 0 23 * * *",             IngestionRun.JOB_NEWS_OUTCOME),
            new JobSpec("insight-claim-score",   "Score matured insight claims",              "0 30 23 * * *",            IngestionRun.JOB_INSIGHT_EVAL),
            new JobSpec("policy-sync",           "Crawl & ingest policy documents",           "0 15 8,14,20 * * MON-SAT", null)
    );

    private record JobSpec(String name, String description, String cron, String ingestionJobName) {}

    private static final Map<String, String> INGESTION_JOB_MAP = Map.of(
            "rag-outcome-enrich",   IngestionRun.JOB_NEWS_OUTCOME,
            "insight-claim-score",  IngestionRun.JOB_INSIGHT_EVAL
    );

    public List<SchedulerJobStatus> listJobs() {
        return JOB_REGISTRY.stream()
                .map(spec -> {
                    LocalDateTime lastRun = resolveLastRun(spec.ingestionJobName());
                    return new SchedulerJobStatus(
                            spec.name(), spec.description(), spec.cron(), lastRun, true);
                })
                .toList();
    }

    private LocalDateTime resolveLastRun(String ingestionJobName) {
        if (ingestionJobName == null) return null;
        return ingestionRunRepository.findTopByJobNameOrderByBusinessDateDesc(ingestionJobName)
                .map(run -> run.getFinishedAt() != null ? run.getFinishedAt() : run.getStartedAt())
                .orElse(null);
    }

    @Async
    public void triggerJob(String jobName) {
        log.info("[AdminScheduler] Manual trigger: {}", jobName);
        switch (jobName) {
            case "premarket-news"       -> cfoScheduler.fetchPreMarketNews();
            case "groww-sync-premarket" -> cfoScheduler.syncPreMarket();
            case "morning-brief"        -> cfoScheduler.generateMorningBrief();
            case "intraday-news"        -> cfoScheduler.fetchIntradayNews();
            case "price-fetch"          -> cfoScheduler.fetchStockPrices();
            case "postmarket-news"      -> cfoScheduler.fetchPostMarketNews();
            case "post-close-insight"   -> cfoScheduler.generatePostCloseInsight();
            case "after-hours-digest"   -> cfoScheduler.generateAfterHoursDigest();
            case "macro-daily"          -> cfoScheduler.refreshMacroState();
            case "macro-monthly"        -> cfoScheduler.refreshMacroMonthly();
            case "fii-dii-flows"        -> cfoScheduler.fetchInstitutionalFlows();
            case "weekly-goal-review"   -> cfoScheduler.weeklyGoalReview();
            case "weekly-peer-refresh"  -> cfoScheduler.weeklyPeerUniverseRefresh();
            case "rag-outcome-enrich"   -> cfoScheduler.enrichNewsOutcomes();
            case "insight-claim-score"  -> cfoScheduler.scoreInsightClaims();
            case "policy-sync"          -> policyIntelligenceScheduler.syncPolicySources();
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown job: " + jobName);
        }
        log.info("[AdminScheduler] Manual trigger complete: {}", jobName);
    }
}
