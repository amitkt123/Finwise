package org.amit.finwise.marketdata.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.ops.GapRepairService;
import org.amit.finwise.marketdata.ops.PgBackupService;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.service.CorporateActionService;
import org.amit.finwise.marketdata.service.CorporateEventService;
import org.amit.finwise.marketdata.service.EodIngestionService;
import org.amit.finwise.marketdata.service.FilingsService;
import org.amit.finwise.marketdata.service.MarketDataSeedService;
import org.amit.finwise.marketdata.service.MfNavService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Daily EOD ingestion with built-in gap repair: every evening (after NSE
 * publishes the bhavcopy, typically by ~18:00 IST) it re-attempts every
 * weekday in the trailing window that is not yet SUCCESS. A holiday stays
 * NO_DATA after re-checking; a day the app was down gets back-filled
 * automatically; "file not yet published" self-heals the next evening.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketDataScheduler {

    private static final int REPAIR_WINDOW_DAYS = 10;
    /** Daily CA pull covers recent updates plus already-announced forward ex-dates. */
    private static final int CA_LOOKBACK_DAYS = 10;
    private static final int CA_LOOKAHEAD_DAYS = 60;
    /** Filings (announcements, deals) trailing window for the daily refresh. */
    private static final int FILINGS_LOOKBACK_DAYS = 7;
    /** Shareholding patterns are quarterly; a wide window catches late/revised filings. */
    private static final int SHP_LOOKBACK_DAYS = 120;

    private final EodIngestionService ingestionService;
    private final MarketDataSeedService seedService;
    private final CorporateActionService corporateActionService;
    private final CorporateEventService corporateEventService;
    private final MfNavService mfNavService;
    private final FilingsService filingsService;
    private final GapRepairService gapRepairService;
    private final PgBackupService pgBackupService;
    private final IngestionRunRepository runRepo;

    @Scheduled(cron = "${marketdata.eod.cron:0 45 18 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailyEodIngestion() {
        if (seedService.isRunning()) {
            log.info("[MarketData] Seed walk in progress; skipping daily EOD run");
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        for (LocalDate date = today.minusDays(REPAIR_WINDOW_DAYS); !date.isAfter(today);
             date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;

            if (!isSuccess(IngestionRun.JOB_BHAVCOPY, date)) {
                EodIngestionService.IngestResult r = ingestionService.ingestBhavcopy(date, true);
                log.info("[MarketData] EOD bhavcopy {}: {} ({} rows)", date, r.status(), r.rows());
                pause();
            }
            if (!isSuccess(IngestionRun.JOB_INDEX_CLOSE, date)) {
                EodIngestionService.IngestResult r = ingestionService.ingestIndexCloseAll(date, true);
                log.info("[MarketData] EOD index-close {}: {} ({} rows)", date, r.status(), r.rows());
                pause();
            }
            if (isSuccess(IngestionRun.JOB_BHAVCOPY, date)
                    && !isSuccess(IngestionRun.JOB_DELIVERY, date)) {
                EodIngestionService.IngestResult r = ingestionService.ingestDelivery(date, true);
                log.info("[MarketData] EOD delivery {}: {} ({} rows)", date, r.status(), r.rows());
                pause();
            }
        }
    }

    /**
     * Daily corporate-actions + event-calendar refresh, after the EOD bhavcopy run.
     * The CA pull spans a trailing + forward window so newly announced ex-dates and
     * late corrections both land; adj_close is recomputed inline for touched names.
     */
    @Scheduled(cron = "${marketdata.ca.cron:0 15 19 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailyCorporateActions() {
        if (seedService.isRunning() || corporateActionService.isSeedRunning()) {
            log.info("[MarketData] Seed in progress; skipping daily CA/event run");
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        CorporateActionService.IngestResult ca = corporateActionService.ingestRange(
                today.minusDays(CA_LOOKBACK_DAYS), today.plusDays(CA_LOOKAHEAD_DAYS), true);
        log.info("[MarketData] Daily corporate actions: {} ({} rows)", ca.status(), ca.rows());
        pause();
        CorporateEventService.ImportResult ev = corporateEventService.ingestEventCalendar();
        log.info("[MarketData] Daily event calendar: {} ({} rows)", ev.status(), ev.rows());
    }

    /**
     * Daily AMFI NAV drop (DF-4). NAVAll.txt is typically published by ~21:00 IST;
     * the job is idempotent on the file's own NAV date, so a re-run is a no-op.
     */
    @Scheduled(cron = "${marketdata.amfi.cron:0 30 21 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailyMfNav() {
        if (mfNavService.isSeedRunning()) {
            log.info("[MarketData] AMFI seed in progress; skipping daily NAV run");
            return;
        }
        MfNavService.IngestResult r = mfNavService.ingestDailyNavAll(false);
        log.info("[MarketData] Daily AMFI NAV: {} ({} NAV rows)", r.status(), r.rows());
    }

    /**
     * Daily filings refresh (DF-4): corporate announcements and bulk/block deals
     * over a short trailing window. Shareholding patterns are pulled weekly (they
     * are quarterly) by {@link #weeklyShareholding()}.
     */
    @Scheduled(cron = "${marketdata.filings.cron:0 45 19 * * MON-FRI}", zone = "Asia/Kolkata")
    public void dailyFilings() {
        if (seedService.isRunning() || corporateActionService.isSeedRunning()) {
            log.info("[MarketData] Seed in progress; skipping daily filings run");
            return;
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDate from = today.minusDays(FILINGS_LOOKBACK_DAYS);
        FilingsService.IngestResult ann = filingsService.ingestAnnouncements(from, today);
        log.info("[MarketData] Daily announcements: {} ({} rows)", ann.status(), ann.rows());
        pause();
        filingsService.ingestDeals(from, today)
                .forEach(r -> log.info("[MarketData] Daily deals: {} ({} rows)", r.status(), r.rows()));
    }

    /** Weekly shareholding-pattern pull (DF-4); quarterly data, late filings trickle in. */
    @Scheduled(cron = "${marketdata.shareholding.cron:0 0 20 * * SAT}", zone = "Asia/Kolkata")
    public void weeklyShareholding() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        FilingsService.IngestResult r =
                filingsService.ingestShareholding(today.minusDays(SHP_LOOKBACK_DAYS), today);
        log.info("[MarketData] Weekly shareholding: {} ({} rows)", r.status(), r.rows());
    }

    /**
     * Nightly self-healing gap repair (DF-5): re-attempt every daily-job hole in
     * the trailing window. Runs after the EOD/filings jobs have had their chance.
     */
    @Scheduled(cron = "${marketdata.gap-repair.cron:0 30 22 * * *}", zone = "Asia/Kolkata")
    public void nightlyGapRepair() {
        if (seedService.isRunning() || corporateActionService.isSeedRunning()
                || mfNavService.isSeedRunning()) {
            log.info("[MarketData] Seed in progress; skipping nightly gap repair");
            return;
        }
        GapRepairService.RepairReport report = gapRepairService.repairGaps();
        log.info("[MarketData] Nightly gap repair: gaps={} repaired={} stillFailing={}",
                report.gapsFound(), report.repaired(), report.stillFailing());
    }

    /** Nightly Postgres backup (DF-5); a no-op unless marketdata.backup.enabled=true. */
    @Scheduled(cron = "${marketdata.backup.cron:0 0 1 * * *}", zone = "Asia/Kolkata")
    public void nightlyBackup() {
        pgBackupService.dumpNow();
    }

    /**
     * On startup, run gap repair once (off the main thread) so an app that was
     * down across one or more trading days back-fills itself instead of waiting
     * for the nightly window. Skipped while any seed is running.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void startupGapRepair() {
        if (seedService.isRunning() || corporateActionService.isSeedRunning()
                || mfNavService.isSeedRunning()) {
            return;
        }
        log.info("[MarketData] Startup gap repair beginning");
        try {
            GapRepairService.RepairReport report = gapRepairService.repairGaps();
            log.info("[MarketData] Startup gap repair: gaps={} repaired={} stillFailing={}",
                    report.gapsFound(), report.repaired(), report.stillFailing());
        } catch (RuntimeException e) {
            log.error("[MarketData] Startup gap repair failed: {}", e.getMessage());
        }
    }

    private boolean isSuccess(String job, LocalDate date) {
        return runRepo.findByJobNameAndBusinessDate(job, date)
                .map(r -> r.getStatus() == IngestionRun.Status.SUCCESS)
                .orElse(false);
    }

    private void pause() {
        try {
            Thread.sleep(800);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
