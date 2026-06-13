package org.amit.finwise.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-off (but resumable) historical seeder: walks every weekday in
 * [from, to], ingesting the full-market bhavcopy and the all-indices close
 * file for each. Dates already recorded SUCCESS/NO_DATA are skipped without a
 * network call, so the walk can be stopped and restarted freely.
 *
 * Throttled to stay polite to NSE's archive hosts (~2 requests per date).
 * A long run of consecutive *failures* (not holidays) aborts the walk — that
 * pattern means a format change or an IP block, and hammering on won't help.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataSeedService {

    private static final int MAX_CONSECUTIVE_FAILURES = 15;

    private final EodIngestionService ingestionService;

    @Value("${marketdata.seed.throttle-ms:800}")
    private long throttleMs;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;

    // progress, read by the status endpoint
    private volatile LocalDate seedFrom;
    private volatile LocalDate seedTo;
    private volatile LocalDate currentDate;
    private volatile int datesProcessed;
    private volatile int datesSkipped;
    private volatile int datesFailed;
    private volatile long rowsSaved;
    private volatile String lastError;
    private volatile String finishedReason;

    /** Starts the seed walk on a background thread. Returns false if already running. */
    public boolean start(LocalDate from, LocalDate to) {
        if (!running.compareAndSet(false, true)) return false;
        stopRequested = false;
        seedFrom = from;
        seedTo = to;
        currentDate = null;
        datesProcessed = 0;
        datesSkipped = 0;
        datesFailed = 0;
        rowsSaved = 0;
        lastError = null;
        finishedReason = null;

        Thread worker = new Thread(() -> {
            try {
                walk(from, to);
            } finally {
                running.set(false);
            }
        }, "marketdata-seeder");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public void requestStop() { stopRequested = true; }

    public boolean isRunning() { return running.get(); }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", running.get());
        s.put("from", seedFrom);
        s.put("to", seedTo);
        s.put("currentDate", currentDate);
        s.put("datesProcessed", datesProcessed);
        s.put("datesSkipped", datesSkipped);
        s.put("datesFailed", datesFailed);
        s.put("rowsSaved", rowsSaved);
        s.put("lastError", lastError);
        s.put("finishedReason", finishedReason);
        return s;
    }

    private void walk(LocalDate from, LocalDate to) {
        log.info("[MarketData] Seed walk starting: {} -> {}", from, to);
        int consecutiveFailures = 0;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (stopRequested) {
                finishedReason = "stopped by request at " + date;
                log.info("[MarketData] Seed walk stopped by request at {}", date);
                return;
            }
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;

            currentDate = date;

            EodIngestionService.IngestResult bhav =
                    ingestionService.ingestBhavcopy(date, false);
            if (!bhav.skipped()) sleep();

            EodIngestionService.IngestResult idx =
                    ingestionService.ingestIndexCloseAll(date, false);
            if (!idx.skipped()) sleep();

            EodIngestionService.IngestResult deliv =
                    ingestionService.ingestDelivery(date, false);
            if (!deliv.skipped()) sleep();

            if (bhav.skipped() && idx.skipped() && deliv.skipped()) {
                datesSkipped++;
            } else {
                datesProcessed++;
                rowsSaved += bhav.rows() + idx.rows();
            }

            boolean failed = bhav.status() == IngestionRun.Status.FAILED
                    || idx.status() == IngestionRun.Status.FAILED
                    || deliv.status() == IngestionRun.Status.FAILED;
            if (failed) {
                datesFailed++;
                consecutiveFailures++;
                lastError = date + ": bhavcopy=" + bhav.status() + ", index=" + idx.status();
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    finishedReason = "aborted: " + consecutiveFailures
                            + " consecutive failures (format change or IP block?)";
                    log.error("[MarketData] Seed walk aborted at {}: {} consecutive failures",
                            date, consecutiveFailures);
                    return;
                }
            } else {
                consecutiveFailures = 0;
            }

            if (datesProcessed > 0 && datesProcessed % 50 == 0) {
                log.info("[MarketData] Seed progress: at {}, processed={}, skipped={}, failed={}, rows={}",
                        date, datesProcessed, datesSkipped, datesFailed, rowsSaved);
            }
        }
        finishedReason = "completed";
        log.info("[MarketData] Seed walk completed: processed={}, skipped={}, failed={}, rows={}",
                datesProcessed, datesSkipped, datesFailed, rowsSaved);
    }

    private void sleep() {
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }
}
