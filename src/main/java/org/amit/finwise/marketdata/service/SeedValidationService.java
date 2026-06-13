package org.amit.finwise.marketdata.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Post-seed validation gate: the checks that must pass before quant consumers
 * (return series, risk engine, factor model) are pointed at md_eod_price.
 *
 *  1. Coverage — every weekday between the seed bounds is accounted for
 *     (SUCCESS or NO_DATA holiday); never-attempted dates are listed.
 *  2. Row-count bands — a SUCCESS bhavcopy day with suspiciously few rows
 *     means a truncated file, not a quiet market (~1,500 EQ+BE rows in 2020
 *     growing to ~2,700 by 2026; the floor catches truncation).
 *  3. Failures — any FAILED ledger rows, listed for manual repair.
 */
@Service
@RequiredArgsConstructor
public class SeedValidationService {

    /** SUCCESS bhavcopy days below this row count indicate a truncated file. */
    private static final int MIN_PLAUSIBLE_ROWS = 1200;

    /** NSE has ~14-16 trading holidays/year; far more NO_DATA weekdays than that is suspicious. */
    private static final int MAX_PLAUSIBLE_HOLIDAYS_PER_YEAR = 20;

    private final IngestionRunRepository runRepo;
    private final EodPriceRepository eodRepo;

    public Map<String, Object> validate() {
        Map<String, Object> report = new LinkedHashMap<>();

        List<IngestionRun> bhavRuns = runRepo.findByJobName(IngestionRun.JOB_BHAVCOPY);
        if (bhavRuns.isEmpty()) {
            report.put("verdict", "NO_DATA — nothing ingested yet");
            return report;
        }

        LocalDate min = bhavRuns.stream().map(IngestionRun::getBusinessDate)
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate max = bhavRuns.stream().map(IngestionRun::getBusinessDate)
                .max(LocalDate::compareTo).orElseThrow();
        Set<LocalDate> attempted = bhavRuns.stream()
                .map(IngestionRun::getBusinessDate).collect(Collectors.toSet());

        // 1. Coverage
        List<LocalDate> neverAttempted = new ArrayList<>();
        int weekdays = 0;
        for (LocalDate d = min; !d.isAfter(max); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            weekdays++;
            if (!attempted.contains(d)) neverAttempted.add(d);
        }
        long successDays = bhavRuns.stream()
                .filter(r -> r.getStatus() == IngestionRun.Status.SUCCESS).count();
        long noDataDays = bhavRuns.stream()
                .filter(r -> r.getStatus() == IngestionRun.Status.NO_DATA).count();

        report.put("range", min + " .. " + max);
        report.put("weekdaysInRange", weekdays);
        report.put("tradingDaysIngested", successDays);
        report.put("holidayDays", noDataDays);
        report.put("neverAttemptedDates", neverAttempted);

        // sanity: holidays per year within plausible band
        double years = Math.max(weekdays / 261.0, 0.1);
        boolean holidaysPlausible = noDataDays / years <= MAX_PLAUSIBLE_HOLIDAYS_PER_YEAR;
        report.put("holidaysPerYear", Math.round(noDataDays / years * 10) / 10.0);

        // 2. Row-count bands
        List<Map<String, Object>> truncated = runRepo
                .findByJobNameAndStatusAndRowCountLessThan(
                        IngestionRun.JOB_BHAVCOPY, IngestionRun.Status.SUCCESS, MIN_PLAUSIBLE_ROWS)
                .stream()
                .map(r -> Map.<String, Object>of(
                        "date", r.getBusinessDate(), "rows", r.getRowCount()))
                .toList();
        report.put("suspectTruncatedDays", truncated);

        // 3. Failures across all three jobs
        Map<String, List<LocalDate>> failures = new LinkedHashMap<>();
        for (String job : List.of(IngestionRun.JOB_BHAVCOPY, IngestionRun.JOB_INDEX_CLOSE,
                IngestionRun.JOB_DELIVERY)) {
            List<LocalDate> failedDates = runRepo
                    .findByJobNameAndStatus(job, IngestionRun.Status.FAILED).stream()
                    .map(IngestionRun::getBusinessDate).sorted().toList();
            if (!failedDates.isEmpty()) failures.put(job, failedDates);
        }
        report.put("failedDates", failures);

        report.put("eodRows", eodRepo.count());

        boolean pass = neverAttempted.isEmpty() && truncated.isEmpty()
                && failures.isEmpty() && holidaysPlausible;
        report.put("verdict", pass ? "PASS — safe to point consumers at md_eod_price"
                : "FAIL — repair the listed dates first");
        return report;
    }
}
