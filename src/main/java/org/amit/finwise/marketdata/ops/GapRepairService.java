package org.amit.finwise.marketdata.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.service.EodIngestionService;
import org.amit.finwise.marketdata.service.FilingsService;
import org.amit.finwise.marketdata.service.MfNavService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Self-healing ingestion (DF-5): diffs expected trading days against
 * md_ingestion_run successes per daily job and re-attempts every hole, with
 * exponential backoff. A sustained failure on a {@link IngestionRun#CRITICAL_JOBS
 * critical} job after retries raises one consolidated email alert.
 *
 * Holidays are inferred from the bhavcopy ledger: a weekday recorded NO_DATA for
 * the bhavcopy job was not a trading day, so other jobs are not expected to have
 * data for it. The bhavcopy gaps are therefore repaired first each run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GapRepairService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final EodIngestionService eodService;
    private final MfNavService mfNavService;
    private final FilingsService filingsService;
    private final IngestionRunRepository runRepo;
    private final EmailNotificationService emailService;

    @Value("${marketdata.gap-repair.window-days:21}")
    private int windowDays;

    @Value("${marketdata.gap-repair.max-attempts:3}")
    private int maxAttempts;

    @Value("${marketdata.gap-repair.base-backoff-ms:1500}")
    private long baseBackoffMs;

    public record RepairReport(int gapsFound, int repaired, int stillFailing,
                               List<String> failures) {}

    /**
     * Scan the trailing window and repair every non-SUCCESS daily-job date.
     * Returns a summary; sends a critical-job alert email when any critical gap
     * remains failing after retries.
     */
    public RepairReport repairGaps() {
        LocalDate today = LocalDate.now(IST);
        LocalDate from = today.minusDays(windowDays);
        List<LocalDate> weekdays = weekdays(from, today);

        int gaps = 0, repaired = 0, stillFailing = 0;
        List<String> failures = new ArrayList<>();
        List<String> criticalFailures = new ArrayList<>();
        Set<String> critical = Set.of(IngestionRun.CRITICAL_JOBS);

        // 1) bhavcopy first — its NO_DATA days define the holiday calendar for the rest.
        for (LocalDate d : weekdays) {
            if (isSuccess(IngestionRun.JOB_BHAVCOPY, d)) continue;
            gaps++;
            boolean ok = attempt(() -> eodService.ingestBhavcopy(d, true).status());
            tally(ok, IngestionRun.JOB_BHAVCOPY, d, critical, failures, criticalFailures);
            if (ok) repaired++; else stillFailing++;
        }

        // 2) index-close and delivery on real trading days only.
        for (LocalDate d : weekdays) {
            if (isHoliday(d)) continue;
            if (!isSuccess(IngestionRun.JOB_INDEX_CLOSE, d)) {
                gaps++;
                boolean ok = attempt(() -> eodService.ingestIndexCloseAll(d, true).status());
                tally(ok, IngestionRun.JOB_INDEX_CLOSE, d, critical, failures, criticalFailures);
                if (ok) repaired++; else stillFailing++;
            }
            if (isSuccess(IngestionRun.JOB_BHAVCOPY, d) && !isSuccess(IngestionRun.JOB_DELIVERY, d)) {
                gaps++;
                boolean ok = attempt(() -> eodService.ingestDelivery(d, true).status());
                tally(ok, IngestionRun.JOB_DELIVERY, d, critical, failures, criticalFailures);
                if (ok) repaired++; else stillFailing++;
            }
            // bulk/block deals (one fetch covers both types).
            if (!isSuccess(IngestionRun.JOB_BULK_DEAL, d) || !isSuccess(IngestionRun.JOB_BLOCK_DEAL, d)) {
                gaps++;
                boolean ok = attempt(() -> {
                    var results = filingsService.ingestDeals(d, d);
                    return results.stream().anyMatch(r -> r.status() != IngestionRun.Status.FAILED)
                            ? IngestionRun.Status.SUCCESS : IngestionRun.Status.FAILED;
                });
                tally(ok, IngestionRun.JOB_BULK_DEAL, d, critical, failures, criticalFailures);
                if (ok) repaired++; else stillFailing++;
            }
        }

        // 3) AMFI NAV: NAVAll.txt only carries the latest date, so we can only
        //    self-heal today's gap; older NAV holes need the history seed.
        LocalDate lastTradingDay = lastTradingDay(weekdays);
        if (lastTradingDay != null && !isSuccess(IngestionRun.JOB_AMFI_NAV, lastTradingDay)) {
            gaps++;
            boolean ok = attempt(() -> mfNavService.ingestDailyNavAll(true).status());
            tally(ok, IngestionRun.JOB_AMFI_NAV, lastTradingDay, critical, failures, criticalFailures);
            if (ok) repaired++; else stillFailing++;
        }

        if (!criticalFailures.isEmpty()) {
            emailService.sendPortfolioAlert(
                    "Market-data ingestion failing",
                    "Critical ingestion jobs are still failing after retries:\n\n- "
                            + String.join("\n- ", criticalFailures)
                            + "\n\nThe gap-repair job will retry on its next run.");
        }
        log.info("[GapRepair] window={}d gaps={} repaired={} stillFailing={}",
                windowDays, gaps, repaired, stillFailing);
        return new RepairReport(gaps, repaired, stillFailing, failures);
    }

    /** Open gaps per job for the dashboard, without attempting repair. */
    public Map<String, Object> openGaps() {
        LocalDate today = LocalDate.now(IST);
        List<LocalDate> weekdays = weekdays(today.minusDays(windowDays), today);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String job : IngestionRun.DAILY_TRADING_JOBS) {
            List<LocalDate> missing = new ArrayList<>();
            for (LocalDate d : weekdays) {
                if (!job.equals(IngestionRun.JOB_BHAVCOPY) && isHoliday(d)) continue;
                if (!isSuccess(job, d)) missing.add(d);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("openGapCount", missing.size());
            row.put("openGaps", missing.size() > 20 ? missing.subList(0, 20) : missing);
            out.put(job, row);
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Run the ingest with exponential backoff; true once it returns non-FAILED. */
    private boolean attempt(Supplier<IngestionRun.Status> ingest) {
        long backoff = baseBackoffMs;
        for (int i = 1; i <= maxAttempts; i++) {
            IngestionRun.Status status;
            try {
                status = ingest.get();
            } catch (RuntimeException e) {
                status = IngestionRun.Status.FAILED;
                log.warn("[GapRepair] attempt {}/{} threw: {}", i, maxAttempts, e.getMessage());
            }
            if (status != IngestionRun.Status.FAILED) return true;
            if (i < maxAttempts) {
                sleep(backoff);
                backoff *= 2;
            }
        }
        return false;
    }

    private void tally(boolean ok, String job, LocalDate d, Set<String> critical,
                       List<String> failures, List<String> criticalFailures) {
        if (ok) return;
        String entry = job + " @ " + d;
        failures.add(entry);
        if (critical.contains(job)) criticalFailures.add(entry);
    }

    private boolean isSuccess(String job, LocalDate date) {
        return runRepo.findByJobNameAndBusinessDate(job, date)
                .map(r -> r.getStatus() == IngestionRun.Status.SUCCESS)
                .orElse(false);
    }

    /** A weekday recorded NO_DATA for bhavcopy was an exchange holiday. */
    private boolean isHoliday(LocalDate date) {
        return runRepo.findByJobNameAndBusinessDate(IngestionRun.JOB_BHAVCOPY, date)
                .map(r -> r.getStatus() == IngestionRun.Status.NO_DATA)
                .orElse(false);
    }

    private LocalDate lastTradingDay(List<LocalDate> weekdays) {
        for (int i = weekdays.size() - 1; i >= 0; i--) {
            if (!isHoliday(weekdays.get(i))) return weekdays.get(i);
        }
        return null;
    }

    private static List<LocalDate> weekdays(LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(d);
            }
        }
        return out;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
