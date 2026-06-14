package org.amit.finwise.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.AmfiClient;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.parser.AmfiNavParser;
import org.amit.finwise.marketdata.parser.AmfiNavParser.NavRecord;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.MfSchemeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ingests AMFI NAVs (DF-4): the daily NAVAll.txt drop into md_mf_nav, upserting
 * the md_mf_scheme master in the same pass, plus a tiered, resumable history
 * seed (equity/hybrid categories first — the full ~40k universe is huge).
 *
 * Writes go through JDBC batch upserts keyed on (amfi_code, nav_date) / amfi_code,
 * so daily runs and re-runs are idempotent. The daily job is ledgered in
 * md_ingestion_run under JOB_AMFI_NAV keyed by the file's own NAV date.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfNavService {

    private static final int BATCH_SIZE = 1000;

    /** Default category fragments for the first history-seed tier. */
    private static final List<String> DEFAULT_SEED_TIERS =
            List.of("Equity", "Hybrid", "Solution Oriented", "Index");

    private static final String SCHEME_UPSERT = """
            INSERT INTO md_mf_scheme
              (amfi_code, isin, isin_reinvest, scheme_name, amc, category, created_at, updated_at)
            VALUES (?,?,?,?,?,?, now(), now())
            ON CONFLICT (amfi_code) DO UPDATE SET
              isin = COALESCE(EXCLUDED.isin, md_mf_scheme.isin),
              isin_reinvest = COALESCE(EXCLUDED.isin_reinvest, md_mf_scheme.isin_reinvest),
              scheme_name = EXCLUDED.scheme_name,
              amc = COALESCE(EXCLUDED.amc, md_mf_scheme.amc),
              category = COALESCE(EXCLUDED.category, md_mf_scheme.category),
              updated_at = now()
            """;

    private static final String NAV_UPSERT = """
            INSERT INTO md_mf_nav (amfi_code, nav_date, nav, created_at)
            VALUES (?,?,?, now())
            ON CONFLICT (amfi_code, nav_date) DO UPDATE SET nav = EXCLUDED.nav
            """;

    private final AmfiClient amfiClient;
    private final MfSchemeRepository schemeRepo;
    private final IngestionRunRepository runRepo;
    private final JdbcTemplate jdbc;

    @Value("${marketdata.amfi.seed.throttle-ms:1200}")
    private long seedThrottleMs;

    // history-seed progress (read by the status endpoint)
    private final AtomicBoolean seedRunning = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private volatile String seedStatus = "idle";
    private volatile int chunksDone;
    private volatile long rowsSaved;

    public record IngestResult(IngestionRun.Status status, int rows) {}

    /**
     * Ingest the daily NAVAll.txt: upsert the scheme master and every scheme's
     * latest NAV. The business date is the NAV date AMFI stamps on the rows (the
     * file is published once per trading day), so the ledger reflects coverage of
     * the actual NAV date, not the wall-clock fetch day.
     */
    public synchronized IngestResult ingestDailyNavAll(boolean force) {
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> body = amfiClient.fetchAllNav();
            if (body.isEmpty()) {
                // No business date known without a file — ledger under "today".
                return ledger(LocalDate.now(), IngestionRun.Status.NO_DATA, 0,
                        "empty NAVAll.txt", started);
            }
            List<NavRecord> records = AmfiNavParser.parse(body.get());
            if (records.isEmpty()) {
                return ledger(LocalDate.now(), IngestionRun.Status.FAILED, 0,
                        "NAVAll.txt fetched but 0 rows parsed", started);
            }

            LocalDate navDate = dominantNavDate(records);
            Optional<IngestionRun> existing =
                    runRepo.findByJobNameAndBusinessDate(IngestionRun.JOB_AMFI_NAV, navDate);
            if (!force && existing.isPresent()
                    && existing.get().getStatus() == IngestionRun.Status.SUCCESS) {
                return new IngestResult(IngestionRun.Status.SUCCESS, 0);
            }

            upsertSchemes(records);
            int navRows = upsertNavs(records);
            return ledger(navDate, IngestionRun.Status.SUCCESS, navRows,
                    records.size() + " schemes, navDate=" + navDate, started);
        } catch (RuntimeException e) {
            log.error("[MfNav] daily NAVAll ingestion failed: {}", e.getMessage());
            return ledger(LocalDate.now(), IngestionRun.Status.FAILED, 0,
                    truncate(e.getMessage()), started);
        }
    }

    /**
     * Start the tiered history seed on a daemon thread: walks [from, to] in
     * month chunks, fetching the all-scheme history report and persisting only
     * NAVs for schemes in the configured tier (resolved from the master, which
     * the daily job populates). Mirrors {@code CorporateActionService}.
     */
    public boolean startHistorySeed(LocalDate from, LocalDate to, List<String> tierFragments) {
        if (!seedRunning.compareAndSet(false, true)) return false;
        stopRequested = false;
        seedStatus = "running";
        chunksDone = 0;
        rowsSaved = 0;
        List<String> tiers = (tierFragments == null || tierFragments.isEmpty())
                ? DEFAULT_SEED_TIERS : tierFragments;

        Thread worker = new Thread(() -> {
            try {
                Set<String> tierCodes = resolveTierCodes(tiers);
                if (tierCodes.isEmpty()) {
                    seedStatus = "aborted: no schemes match tier " + tiers
                            + " (run the daily NAV job first to populate the master)";
                    return;
                }
                log.info("[MfNav] History seed {} -> {} for {} schemes (tiers={})",
                        from, to, tierCodes.size(), tiers);
                for (LocalDate start = from; !start.isAfter(to); start = start.plusMonths(1)) {
                    if (stopRequested) { seedStatus = "stopped at " + start; return; }
                    LocalDate end = start.plusMonths(1).minusDays(1);
                    if (end.isAfter(to)) end = to;
                    int rows = ingestHistoryChunk(start, end, tierCodes);
                    chunksDone++;
                    rowsSaved += rows;
                    sleep();
                }
                seedStatus = "completed";
                log.info("[MfNav] History seed completed: {} chunks, {} NAV rows", chunksDone, rowsSaved);
            } catch (RuntimeException e) {
                seedStatus = "failed: " + truncate(e.getMessage());
                log.error("[MfNav] history seed failed: {}", e.getMessage());
            } finally {
                seedRunning.set(false);
            }
        }, "amfi-nav-seeder");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public void requestStop() { stopRequested = true; }

    public boolean isSeedRunning() { return seedRunning.get(); }

    public Map<String, Object> seedStatusView() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", seedRunning.get());
        s.put("status", seedStatus);
        s.put("chunksDone", chunksDone);
        s.put("rowsSaved", rowsSaved);
        return s;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private int ingestHistoryChunk(LocalDate from, LocalDate to, Set<String> tierCodes) {
        Optional<String> body = amfiClient.fetchNavHistory(from, to);
        if (body.isEmpty()) return 0;
        List<NavRecord> records = AmfiNavParser.parse(body.get()).stream()
                .filter(r -> tierCodes.contains(r.amfiCode()))
                .toList();
        // Keep the master fresh for any new schemes the history report surfaces.
        upsertSchemes(records);
        return upsertNavs(records);
    }

    private Set<String> resolveTierCodes(List<String> tierFragments) {
        Set<String> codes = new HashSet<>();
        for (String fragment : tierFragments) {
            schemeRepo.findByCategoryContaining(fragment)
                    .forEach(s -> codes.add(s.getAmfiCode()));
        }
        return codes;
    }

    private void upsertSchemes(List<NavRecord> records) {
        // De-dup by code within the batch; latest sighting wins.
        Map<String, Object[]> bySchemeCode = new LinkedHashMap<>();
        for (NavRecord r : records) {
            bySchemeCode.put(r.amfiCode(), new Object[]{
                    r.amfiCode(), r.isinPayout(), r.isinReinvest(),
                    truncate(r.schemeName(), 300), truncate(r.amc(), 200),
                    truncate(r.category(), 200)});
        }
        batch(SCHEME_UPSERT, new ArrayList<>(bySchemeCode.values()));
    }

    private int upsertNavs(List<NavRecord> records) {
        List<Object[]> args = records.stream()
                .map(r -> new Object[]{r.amfiCode(), r.navDate(), r.nav()})
                .toList();
        batch(NAV_UPSERT, args);
        return args.size();
    }

    private void batch(String sql, List<Object[]> args) {
        for (int from = 0; from < args.size(); from += BATCH_SIZE) {
            jdbc.batchUpdate(sql, args.subList(from, Math.min(from + BATCH_SIZE, args.size())));
        }
    }

    /** The NAV date most rows carry — robust to a few stale/late schemes in the file. */
    private LocalDate dominantNavDate(List<NavRecord> records) {
        Map<LocalDate, Integer> counts = new LinkedHashMap<>();
        for (NavRecord r : records) counts.merge(r.navDate(), 1, Integer::sum);
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(LocalDate.now());
    }

    private IngestResult ledger(LocalDate businessDate, IngestionRun.Status status,
                                int rows, String message, LocalDateTime started) {
        IngestionRun run = runRepo
                .findByJobNameAndBusinessDate(IngestionRun.JOB_AMFI_NAV, businessDate)
                .orElseGet(() -> IngestionRun.builder()
                        .jobName(IngestionRun.JOB_AMFI_NAV).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
        return new IngestResult(status, rows);
    }

    private void sleep() {
        try {
            Thread.sleep(seedThrottleMs);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }

    private static String truncate(String s) {
        return truncate(s, 500);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
