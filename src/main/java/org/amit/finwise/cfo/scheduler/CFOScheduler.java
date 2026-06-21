package org.amit.finwise.cfo.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.auth.User;
import org.amit.finwise.auth.UserRepository;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.amit.finwise.cfo.service.MarketContextService;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.ingestion.GrowwConnector;
import org.amit.finwise.cfo.service.ingestion.NewsAggregatorService;
import org.amit.finwise.cfo.service.llm.LlmRefinementService;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.cfo.service.macro.MacroStateService;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CFOScheduler {

    private final GrowwConnector growwConnector;
    private final NewsAggregatorService newsAggregatorService;
    private final CFOAdvisorService cfoAdvisorService;
    private final EmailNotificationService emailNotificationService;
    private final PortfolioSnapshotRepository snapshotRepository;
    private final LlmRefinementService llmRefinementService;
    private final StockPriceService stockPriceService;
    private final MarketContextService marketContextService;
    private final MacroStateService macroStateService;
    private final MacroSeriesService macroSeriesService;
    private final org.amit.finwise.cfo.service.research.PeerUniverseService peerUniverseService;
    private final org.amit.finwise.cfo.service.rag.EventOutcomeService eventOutcomeService;
    private final org.amit.finwise.cfo.service.InsightEvaluationService insightEvaluationService;
    private final org.amit.finwise.cfo.config.FactorProperties factorProperties;
    private final UserRepository userRepository;

    // ── Morning Pipeline ──────────────────────────────────────────────────────

    @Scheduled(cron = "${cfo.schedule.news.premarket:0 0 7 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchPreMarketNews() {
        log.info("[CFO] Fetching pre-market news...");
        try {
            int count = newsAggregatorService.fetchAndStoreNews();
            llmRefinementService.refineRecentArticles();
            log.info("[CFO] Pre-market news: {} new articles", count);
        } catch (Exception e) {
            log.error("[CFO] Pre-market news fetch failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 15 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncPreMarket() {
        forEachUser(userId -> syncGroww(userId, "pre-market"));
    }

    @Scheduled(cron = "0 30 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void generateMorningBrief() {
        log.info("[CFO] Generating daily morning brief for all users...");
        forEachUser(userId -> {
            AiInsight brief = cfoAdvisorService.generateDailyBrief(userId);
            log.info("[CFO] Morning brief generated for {}: {}", userId, brief.getTitle());
            String email = resolveUserEmail(userId);
            emailNotificationService.sendDailyBrief(brief, email);
        });
    }

    // ── Intraday News (every 30 min, market hours) ────────────────────────────

    @Scheduled(cron = "${cfo.news.fetch-cron:0 0/30 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchIntradayNews() {
        int count = newsAggregatorService.fetchAndStoreNews();
        log.info("[CFO] Intraday news fetch: {} new articles", count);
        llmRefinementService.refineRecentArticles();
    }

    // ── Groww Portfolio Syncs ─────────────────────────────────────────────────

    @Scheduled(cron = "${cfo.schedule.sync.open:0 15 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void syncMarketOpen() {
        forEachUser(userId -> syncGroww(userId, "market-open"));
    }

    @Scheduled(cron = "0 30 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncMidSessionAndInsight() {
        forEachUser(userId -> {
            syncGroww(userId, "mid-session");
            generateMarketInsight(userId, "Mid-Day");
        });
    }

    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncPreClose() {
        forEachUser(userId -> syncGroww(userId, "pre-close"));
    }

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncMarketClose() {
        forEachUser(userId -> syncGroww(userId, "market-close"));
    }

    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncAfterSettlement() {
        forEachUser(userId -> syncGroww(userId, "after-settlement"));
    }

    // ── Price Data + Post-Close Pipeline ─────────────────────────────────────

    @Scheduled(cron = "${cfo.schedule.price.fetch:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchStockPrices() {
        log.info("[CFO] Fetching stock price history (provider chain: {})...",
                stockPriceService.getProviderChain());

        // Per-user: fetch prices for each user's holdings
        forEachUser(userId -> {
            try {
                stockPriceService.fetchAndPersistPrices(userId);
                log.info("[CFO] Stock price fetch complete for {}", userId);
            } catch (Exception e) {
                log.error("[CFO] Stock price fetch failed for {}: {}", userId, e.getMessage());
            }
        });

        // Global: benchmark and factor indices — run once, not per user
        try {
            int saved = stockPriceService.fetchAndPersistBenchmark(730);
            log.info("[CFO] Nifty 50 benchmark fetch complete: {} new records", saved);
        } catch (Exception e) {
            log.error("[CFO] Nifty 50 benchmark fetch failed: {}", e.getMessage());
        }

        try {
            List<String> factorIndices = factorProperties.getIndices().stream()
                    .filter(idx -> !StockPriceService.NIFTY_SYMBOL.equals(idx))
                    .toList();
            int saved = stockPriceService.fetchAndPersistIndices(
                    factorIndices, factorProperties.getLookbackDays());
            log.info("[CFO] Factor index fetch complete ({} indices): {} new records",
                    factorIndices.size(), saved);
        } catch (Exception e) {
            log.error("[CFO] Factor index fetch failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 15 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchPostMarketNews() {
        log.info("[CFO] Fetching post-market news...");
        try {
            int count = newsAggregatorService.fetchAndStoreNews();
            llmRefinementService.refineRecentArticles();
            log.info("[CFO] Post-market news: {} new articles", count);
        } catch (Exception e) {
            log.error("[CFO] Post-market news fetch failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void generatePostCloseInsight() {
        forEachUser(userId -> generateMarketInsight(userId, "Post-Close"));
    }

    // ── After-Hours Digest ────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Kolkata")
    public void generateAfterHoursDigest() {
        log.info("[CFO] Generating after-hours insights for all users...");
        forEachUser(userId -> {
            AiInsight insight = cfoAdvisorService.generateAfterHoursInsights(userId);
            PortfolioSnapshot snapshot = snapshotRepository
                    .findTopByUserIdOrderBySnapshotTimeDesc(userId)
                    .orElse(null);
            String email = resolveUserEmail(userId);
            emailNotificationService.sendAfterHoursInsight(insight, snapshot, email);
            log.info("[CFO] After-hours digest sent for {}", userId);
        });
    }

    @Scheduled(fixedRateString = "${cfo.schedule.market-context.interval-ms:21600000}")
    public void refreshMarketContext() {
        log.debug("[CFO] Invalidating market context cache");
        marketContextService.invalidateCache();
    }

    // ── Macro State ──────────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void refreshMacroState() {
        log.info("[CFO] Refreshing macro state snapshot...");
        try {
            macroSeriesService.ingestDaily();
            org.amit.finwise.cfo.model.MacroSnapshot snapshot = macroStateService.fetchAndPersistDaily();
            log.info("[CFO] Macro snapshot updated: repo={}, CPI={}, USD/INR={}, VIX={}",
                    snapshot.getRepoRate(),
                    snapshot.getCpiYoY(),
                    snapshot.getUsdInr(),
                    snapshot.getIndiaVix());
        } catch (Exception e) {
            log.error("[CFO] Macro state refresh failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 18 15 * *", zone = "Asia/Kolkata")
    public void refreshMacroMonthly() {
        log.info("[CFO] Refreshing monthly MOSPI macro series...");
        try {
            MacroSeriesService.IngestResult r = macroSeriesService.ingestMonthly();
            log.info("[CFO] MOSPI ingest: {} observations across {} series",
                    r.observations(), r.seriesTouched());
        } catch (Exception e) {
            log.error("[CFO] Monthly MOSPI refresh failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchInstitutionalFlows() {
        log.info("[CFO] Fetching FII/DII institutional flows...");
        try {
            macroStateService.updateFlows();
        } catch (Exception e) {
            log.error("[CFO] FII/DII flow update failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 10 * * SUN", zone = "Asia/Kolkata")
    public void weeklyPeerUniverseRefresh() {
        log.info("[CFO] Refreshing peer valuation universe for all users...");
        forEachUser(userId -> {
            try {
                peerUniverseService.refreshForUser(userId);
            } catch (Exception e) {
                log.error("[CFO] Peer universe refresh failed for {}: {}", userId, e.getMessage());
            }
        });
    }

    @Scheduled(cron = "${cfo.rag.outcome-cron:0 0 23 * * *}", zone = "Asia/Kolkata")
    public void enrichNewsOutcomes() {
        log.info("[CFO] Enriching news event outcomes...");
        try {
            var result = eventOutcomeService.enrichOutcomes();
            log.info("[CFO] News outcome enrichment: {} clusters scanned, {} outcomes written",
                    result.clustersScanned(), result.outcomesWritten());
        } catch (Exception e) {
            log.error("[CFO] News outcome enrichment failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${cfo.eval.score-cron:0 30 23 * * *}", zone = "Asia/Kolkata")
    public void scoreInsightClaims() {
        log.info("[CFO] Scoring insight claims...");
        try {
            var result = insightEvaluationService.scoreMaturedClaims();
            log.info("[CFO] Insight scoring: {} pending, {} scored, {} abandoned",
                    result.scanned(), result.scored(), result.abandoned());
            insightEvaluationService.calibrationReport(90)
                    .forEach(row -> log.info("[CFO][calibration] {}", row.render()));
        } catch (Exception e) {
            log.error("[CFO] Insight scoring failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 9 * * SUN", zone = "Asia/Kolkata")
    public void weeklyGoalReview() {
        log.info("[CFO] Running weekly goal review for all users...");
        forEachUser(userId -> {
            AiInsight advice = cfoAdvisorService.generateGoalAdvice(userId);
            if (advice != null) {
                String email = resolveUserEmail(userId);
                emailNotificationService.sendDailyBrief(advice, email);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void generateMarketInsight(String userId, String label) {
        log.info("[CFO] Generating {} market insight for {}...", label, userId);
        try {
            AiInsight insight = cfoAdvisorService.generateMarketInsight(label, userId);
            log.info("[CFO] {} market insight generated for {}: {}", label, userId, insight.getTitle());
        } catch (Exception e) {
            log.error("[CFO] {} market insight failed for {}: {}", label, userId, e.getMessage());
        }
    }

    private void syncGroww(String userId, String checkpoint) {
        log.info("[CFO] Groww sync ({}) for {}...", checkpoint, userId);
        try {
            PortfolioSnapshot snapshot = growwConnector.syncHoldings(userId);
            if (snapshot != null) {
                log.info("[CFO] Groww {} sync done for {}: value=₹{}", checkpoint, userId, snapshot.getCurrentValue());
            }
        } catch (IllegalStateException e) {
            log.warn("[CFO] Groww sync skipped ({}) for {}: {}", checkpoint, userId, e.getMessage());
        } catch (Exception e) {
            log.error("[CFO] Groww sync failed ({}) for {}: {}", checkpoint, userId, e.getMessage());
        }
    }

    /**
     * Iterate over all enabled users, wrapping each in try/catch so one user's
     * failure does not abort the batch.
     */
    private void forEachUser(java.util.function.Consumer<String> action) {
        List<User> users = userRepository.findAllByEnabledTrue();
        for (User user : users) {
            try {
                action.accept(user.getUsername());
            } catch (Exception e) {
                log.error("[CFO] Scheduled task failed for user {}: {}", user.getUsername(), e.getMessage());
            }
        }
    }

    /**
     * Resolve the recipient email for a user. Looks up the UserProfile first,
     * falls back to the User entity's email.
     */
    private String resolveUserEmail(String userId) {
        try {
            return userRepository.findByUsername(userId)
                    .map(org.amit.finwise.auth.User::getEmail)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[CFO] Could not resolve email for {}: {}", userId, e.getMessage());
            return null;
        }
    }
}
