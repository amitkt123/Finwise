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
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.cfo.service.macro.MacroStateService;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
import org.springframework.beans.factory.annotation.Value;
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
    private final org.amit.finwise.cfo.config.FactorProperties factorProperties;

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
     * Also fetches 365 days of Nifty 50 benchmark history for risk engine beta/tracking-error.
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

        // Fetch Nifty 50 benchmark — UPSERT semantics, only new dates are saved.
        // 730 days so benchmark TWRR covers the full performance lookback window.
        try {
            int saved = stockPriceService.fetchAndPersistBenchmark(730);
            log.info("[CFO] Nifty 50 benchmark fetch complete: {} new records", saved);
        } catch (Exception e) {
            log.error("[CFO] Nifty 50 benchmark fetch failed: {}", e.getMessage());
        }

        // Factor-model index universe (Phase 8) — sector/style indices for the
        // multi-factor risk model. Missing tickers are dropped silently; the
        // model degrades to the factors that have data.
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

    // ── Macro State (Phase 5) ────────────────────────────────────────────

    /**
     * 5:00 PM IST — Refresh macro state after market close.
     * First ingests the daily macro_series feeds (FBIL G-sec curve + USD/INR,
     * RBI policy rates, India VIX from the index file), then assembles the
     * snapshot from macro_series (DF-3).
     */
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

    /**
     * Monthly — pull MOSPI CPI/IIP/WPI history from data.gov.in into macro_series.
     * One call seeds 3y+ and refreshes the latest print; runs on the 15th
     * (after the mid-month CPI/IIP releases) at 18:00 IST.
     */
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

    /**
     * 7:00 PM IST weekdays — pull FII/DII institutional flows from NSE.
     * NSE publishes fiidiiTradeReact data post-market (~18:30); this updates
     * today's macro snapshot in place and recomputes the 5-day FII cumulative.
     */
    @Scheduled(cron = "0 0 19 * * MON-FRI", zone = "Asia/Kolkata")
    public void fetchInstitutionalFlows() {
        log.info("[CFO] Fetching FII/DII institutional flows...");
        try {
            macroStateService.updateFlows();
        } catch (Exception e) {
            log.error("[CFO] FII/DII flow update failed: {}", e.getMessage());
        }
    }

    /**
     * Weekly: Every Sunday 10 AM IST — refresh the peer-valuation universe.
     * Walks every gazetteer peer of the user's held sectors through the
     * daily-cached fundamentals fetch (rate-limited), then recomputes the
     * cross-sectional P/E and P/B percentiles.
     */
    @Scheduled(cron = "0 0 10 * * SUN", zone = "Asia/Kolkata")
    public void weeklyPeerUniverseRefresh() {
        log.info("[CFO] Refreshing peer valuation universe...");
        try {
            peerUniverseService.refreshForUser(defaultUserId);
        } catch (Exception e) {
            log.error("[CFO] Peer universe refresh failed: {}", e.getMessage());
        }
    }

    /**
     * 11:00 PM IST daily — outcome enrichment for the news RAG (DF-6).
     * Runs after the EOD bhavcopy (18:45) and gap repair (22:30) have landed
     * prices, so clusters can be scored against realized excess returns vs Nifty.
     */
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
