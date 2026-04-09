package org.amit.expensetracker.cfo.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.expensetracker.cfo.model.AiInsight;
import org.amit.expensetracker.cfo.model.PortfolioSnapshot;
import org.amit.expensetracker.cfo.repository.PortfolioSnapshotRepository;
import org.amit.expensetracker.cfo.service.CFOAdvisorService;
import org.amit.expensetracker.cfo.service.MarketContextService;
import org.amit.expensetracker.cfo.service.StockPriceService;
import org.amit.expensetracker.cfo.service.ingestion.GrowwConnector;
import org.amit.expensetracker.cfo.service.ingestion.NewsAggregatorService;
import org.amit.expensetracker.cfo.service.llm.LlmRefinementService;
import org.amit.expensetracker.cfo.service.notification.EmailNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Value("${cfo.user.id}")
    private String defaultUserId;

    // ── News Jobs ─────────────────────────────────────────────────────────────

    /** 7:00 AM IST — Pre-market news fetch */
    @Scheduled(cron = "${cfo.schedule.news.premarket:0 0 7 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchPreMarketNews() {
        log.info("[CFO] Fetching pre-market news...");
        try {
            int count = newsAggregatorService.fetchAndStoreNews();
            log.info("[CFO] Pre-market news: {} new articles", count);
        } catch (Exception e) {
            log.error("[CFO] Pre-market news fetch failed: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "${cfo.news.fetch-cron:0 0/30 6-22 * * MON-SAT}")
    public void fetchNewsJob() {
        int count = newsAggregatorService.fetchAndStoreNews();
        log.info("Fetched {} articles", count);

        // Async: LLM reviews low-confidence articles in background
        llmRefinementService.refineRecentArticles();
    }
    /** 4:00 PM IST — Post-market news fetch */
    @Scheduled(cron = "${cfo.schedule.news.postmarket:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchPostMarketNews() {
        log.info("[CFO] Fetching post-market news...");
        try {
            int count = newsAggregatorService.fetchAndStoreNews();
            log.info("[CFO] Post-market news: {} new articles", count);
        } catch (Exception e) {
            log.error("[CFO] Post-market news fetch failed: {}", e.getMessage());
        }
    }

    // ── Daily Brief ────────────────────────────────────────────────────────────

    /** 7:30 AM IST — Generate morning CFO brief */
    @Scheduled(cron = "0 30 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void generateMorningBrief() {
        log.info("[CFO] Generating daily morning brief...");
        try {
            AiInsight brief = cfoAdvisorService.generateDailyBrief();
            log.info("[CFO] Morning brief generated: {}", brief.getTitle());
        } catch (Exception e) {
            log.error("[CFO] Morning brief generation failed: {}", e.getMessage());
        }
    }

    // ── Groww Portfolio Syncs (5x/day on market days) ─────────────────────────

    /** 9:15 AM IST — Market open */
    @Scheduled(cron = "${cfo.schedule.sync.open:0 15 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void syncMarketOpen() {
        syncGroww("market-open");
    }

    /** 12:30 PM IST — Mid-session */
    @Scheduled(cron = "0 30 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncMidSession() {
        syncGroww("mid-session");
    }

    /** 3:00 PM IST — Pre-close */
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncPreClose() {
        syncGroww("pre-close");
    }

    /** 3:30 PM IST — Market close */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncMarketClose() {
        syncGroww("market-close");
    }

    /** 5:30 PM IST — After settlement (final prices) */
    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncAfterSettlement() {
        syncGroww("after-settlement");
    }

    // ── After-Hours Digest ────────────────────────────────────────────────────

    /** 6:00 PM IST — Generate after-hours insights + send email digest */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Kolkata")
    public void generateAfterHoursDigest() {
        log.info("[CFO] Generating after-hours insights and sending email digest...");
        try {
            AiInsight insight = cfoAdvisorService.generateAfterHoursInsights();
            PortfolioSnapshot snapshot = snapshotRepository
                    .findTopByUserIdOrderBySnapshotTimeDesc(defaultUserId)
                    .orElse(null);
            emailNotificationService.sendAfterHoursInsight(insight, snapshot);
            log.info("[CFO] After-hours digest sent");
        } catch (Exception e) {
            log.error("[CFO] After-hours digest failed: {}", e.getMessage());
        }
    }

    // ── Price Data + Market Context ───────────────────────────────────────────

    /** 4:00 PM IST — Fetch stock price history after NSE market close (3:30 PM) */
    @Scheduled(cron = "${cfo.schedule.price.fetch:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchStockPrices() {
        log.info("[CFO] Fetching stock price history (provider chain: {})...",
                stockPriceService.getProviderChain());
        try {
            stockPriceService.fetchAndPersistPrices(defaultUserId);
        } catch (Exception e) {
            log.error("[CFO] Stock price fetch failed: {}", e.getMessage());
        }
    }

    /** Every 6 hours — Invalidate market context cache so it recomputes on next request */
    @Scheduled(fixedRateString = "${cfo.schedule.market-context.interval-ms:21600000}")
    public void refreshMarketContext() {
        log.debug("[CFO] Invalidating market context cache");
        marketContextService.invalidateCache();
    }

    /** Weekly: Every Sunday 9 AM IST — Goal review + advice email */
    @Scheduled(cron = "0 0 9 * * SUN", zone = "Asia/Kolkata")
    public void weeklyGoalReview() {
        log.info("[CFO] Running weekly goal review...");
        try {
            AiInsight advice = cfoAdvisorService.generateGoalAdvice();
            if (advice != null) {
                emailNotificationService.sendDailyBrief(advice);
            }
        } catch (Exception e) {
            log.error("[CFO] Weekly goal review failed: {}", e.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private void syncGroww(String checkpoint) {
        log.info("[CFO] Groww sync ({})...", checkpoint);
        try {
            PortfolioSnapshot snapshot = growwConnector.syncHoldings();
            if (snapshot != null) {
                log.info("[CFO] Groww {} sync done: value=₹{}", checkpoint, snapshot.getCurrentValue());
            }
        } catch (IllegalStateException e) {
            // Token missing — don't spam logs
            log.warn("[CFO] Groww sync skipped ({}): {}", checkpoint, e.getMessage());
        } catch (Exception e) {
            log.error("[CFO] Groww sync failed ({}): {}", checkpoint, e.getMessage());
        }
    }
}
