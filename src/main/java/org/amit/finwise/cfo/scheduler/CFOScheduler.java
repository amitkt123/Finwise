package org.amit.finwise.cfo.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.amit.finwise.cfo.service.MarketContextService;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.ingestion.GrowwConnector;
import org.amit.finwise.cfo.service.ingestion.NewsAggregatorService;
import org.amit.finwise.cfo.service.llm.LlmRefinementService;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
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

    // ── Morning Pipeline ──────────────────────────────────────────────────────
    // Sequence: news → Groww sync → brief (all data fresh before LLM call)

    /**
     * 7:00 AM IST — Fetch pre-market news so the morning brief has today's headlines.
     */
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

    /**
     * 7:15 AM IST — Sync Groww holdings so the morning brief has today's positions.
     * Runs after news fetch (7:00) and before brief generation (7:30).
     */
    @Scheduled(cron = "0 15 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncPreMarket() {
        syncGroww("pre-market");
    }

    /**
     * 7:30 AM IST — Generate morning CFO brief.
     * At this point news (7:00) and Groww data (7:15) are already fresh.
     * Uses a 2-hour cooldown so re-calling the endpoint mid-day produces a fresh brief.
     */
    @Scheduled(cron = "0 30 7 * * MON-FRI", zone = "Asia/Kolkata")
    public void generateMorningBrief() {
        log.info("[CFO] Generating daily morning brief...");
        try {
            AiInsight brief = cfoAdvisorService.generateDailyBrief();
            log.info("[CFO] Morning brief generated: {}", brief.getTitle());
            emailNotificationService.sendDailyBrief(brief);
        } catch (Exception e) {
            log.error("[CFO] Morning brief generation failed: {}", e.getMessage());
        }
    }

    // ── Intraday News (every 30 min, market hours) ────────────────────────────

    @Scheduled(cron = "${cfo.news.fetch-cron:0 0/30 9-15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchIntradayNews() {
        int count = newsAggregatorService.fetchAndStoreNews();
        log.info("[CFO] Intraday news fetch: {} new articles", count);
        llmRefinementService.refineRecentArticles();
    }

    // ── Groww Portfolio Syncs ─────────────────────────────────────────────────

    /** 9:15 AM IST — Market open */
    @Scheduled(cron = "${cfo.schedule.sync.open:0 15 9 * * MON-FRI}", zone = "Asia/Kolkata")
    public void syncMarketOpen() {
        syncGroww("market-open");
    }

    /** 12:30 PM IST — Mid-session sync then generate mid-day market insight */
    @Scheduled(cron = "0 30 12 * * MON-FRI", zone = "Asia/Kolkata")
    public void syncMidSessionAndInsight() {
        syncGroww("mid-session");
        generateMarketInsight("Mid-Day");
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

    // ── Price Data + Post-Close Pipeline ─────────────────────────────────────
    // Sequence: fetch prices (→ updates investments + snapshot) → post-market news → market insight

    /**
     * 4:00 PM IST — Fetch closing prices after NSE market close (3:30 PM).
     * StockPriceService automatically updates Investment.currentPrice and rebuilds
     * the portfolio snapshot after all symbols are fetched.
     */
    @Scheduled(cron = "${cfo.schedule.price.fetch:0 0 16 * * MON-FRI}", zone = "Asia/Kolkata")
    public void fetchStockPrices() {
        log.info("[CFO] Fetching stock price history (provider chain: {})...",
                stockPriceService.getProviderChain());
        try {
            stockPriceService.fetchAndPersistPrices(defaultUserId);
            log.info("[CFO] Stock price fetch complete, investments and snapshot updated");
        } catch (Exception e) {
            log.error("[CFO] Stock price fetch failed: {}", e.getMessage());
        }
    }

    /**
     * 4:15 PM IST — Fetch post-market news after prices are in.
     */
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

    /**
     * 4:30 PM IST — Generate post-close market insight.
     * Prices (4:00), investments, portfolio snapshot, and post-market news (4:15) are all fresh.
     */
    @Scheduled(cron = "0 30 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void generatePostCloseInsight() {
        generateMarketInsight("Post-Close");
    }

    // ── After-Hours Digest ────────────────────────────────────────────────────

    /** 6:00 PM IST — Full after-hours review + email digest */
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void generateMarketInsight(String label) {
        log.info("[CFO] Generating {} market insight...", label);
        try {
            AiInsight insight = cfoAdvisorService.generateMarketInsight(label);
            log.info("[CFO] {} market insight generated: {}", label, insight.getTitle());
        } catch (Exception e) {
            log.error("[CFO] {} market insight failed: {}", label, e.getMessage());
        }
    }

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
