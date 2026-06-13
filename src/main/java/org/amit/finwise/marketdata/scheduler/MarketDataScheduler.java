package org.amit.finwise.marketdata.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.service.CorporateActionService;
import org.amit.finwise.marketdata.service.CorporateEventService;
import org.amit.finwise.marketdata.service.EodIngestionService;
import org.amit.finwise.marketdata.service.MarketDataSeedService;
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

    private final EodIngestionService ingestionService;
    private final MarketDataSeedService seedService;
    private final CorporateActionService corporateActionService;
    private final CorporateEventService corporateEventService;
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
