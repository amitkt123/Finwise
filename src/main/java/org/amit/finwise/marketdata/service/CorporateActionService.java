package org.amit.finwise.marketdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.parser.CorporateActionParser;
import org.amit.finwise.marketdata.parser.CorporateActionParser.CaRecord;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ingests the NSE corporate-actions feed (splits, bonus, rights, dividends) into
 * md_corporate_action, then triggers adj_close recomputation. The feed is
 * fetched in date-range chunks (NSE serves a whole month comfortably) and every
 * chunk is ledgered in md_ingestion_run under JOB_CORPORATE_ACTION, keyed by the
 * chunk's end date — re-running a chunk re-upserts by dedup hash with no dupes.
 *
 * The historical seed runs on a daemon thread (mirroring MarketDataSeedService)
 * and recomputes all adjustments once at the end.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorporateActionService {

    private static final int BATCH_SIZE = 500;
    private static final long THROTTLE_MS = 900;

    private static final String UPSERT = """
            INSERT INTO md_corporate_action
              (instrument_id, isin, symbol, series, company_name, action_type, subject,
               ex_date, record_date, ratio_new, ratio_old, from_face_value, to_face_value,
               dividend_amount, dedup_hash, source, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (dedup_hash) DO UPDATE SET
              instrument_id = EXCLUDED.instrument_id, series = EXCLUDED.series,
              company_name = EXCLUDED.company_name, action_type = EXCLUDED.action_type,
              record_date = EXCLUDED.record_date, ratio_new = EXCLUDED.ratio_new,
              ratio_old = EXCLUDED.ratio_old, from_face_value = EXCLUDED.from_face_value,
              to_face_value = EXCLUDED.to_face_value, dividend_amount = EXCLUDED.dividend_amount
            """;

    private final NseApiClient nseApiClient;
    private final InstrumentRepository instrumentRepo;
    private final IngestionRunRepository runRepo;
    private final PriceAdjustmentService priceAdjustmentService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> isinToInstrumentId = new HashMap<>();
    private boolean cacheLoaded = false;

    // seed progress (read by the status endpoint)
    private final AtomicBoolean seedRunning = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;
    private volatile String seedStatus = "idle";
    private volatile int chunksDone;
    private volatile int rowsSaved;

    public record IngestResult(IngestionRun.Status status, int rows) {}

    /**
     * Ingest one date-range chunk. When recompute=true the affected instruments'
     * adj_close is recomputed inline (used by the daily job); the seed defers
     * recomputation to a single recomputeAll() at the end.
     */
    public synchronized IngestResult ingestRange(LocalDate from, LocalDate to, boolean recompute) {
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> json = nseApiClient.fetchCorporateActions(from, to);
            if (json.isEmpty()) {
                return ledger(to, IngestionRun.Status.NO_DATA, 0,
                        "empty CA response " + from + ".." + to, started);
            }
            List<CaRecord> records = CorporateActionParser.parse(json.get(), objectMapper);
            if (records.isEmpty()) {
                return ledger(to, IngestionRun.Status.SUCCESS, 0,
                        "0 actions " + from + ".." + to, started);
            }

            Set<Long> affected = new HashSet<>();
            List<Object[]> args = new java.util.ArrayList<>(records.size());
            for (CaRecord r : records) {
                Long instrumentId = resolveInstrument(r.isin());
                if (instrumentId != null) affected.add(instrumentId);
                args.add(new Object[]{
                        instrumentId, r.isin(), r.symbol(), r.series(), r.companyName(),
                        r.actionType().name(), r.subject(), r.exDate(), r.recordDate(),
                        r.ratioNew(), r.ratioOld(), r.fromFaceValue(), r.toFaceValue(),
                        r.dividendAmount(), dedupHash(r), "nse_ca"});
            }
            for (int i = 0; i < args.size(); i += BATCH_SIZE) {
                jdbc.batchUpdate(UPSERT, args.subList(i, Math.min(i + BATCH_SIZE, args.size())));
            }

            if (recompute) {
                for (Long id : affected) priceAdjustmentService.recomputeForInstrument(id);
            }
            return ledger(to, IngestionRun.Status.SUCCESS, records.size(),
                    records.size() + " actions " + from + ".." + to, started);
        } catch (RuntimeException e) {
            log.error("[CorpAction] ingest {}..{} failed: {}", from, to, e.getMessage());
            return ledger(to, IngestionRun.Status.FAILED, 0, truncate(e.getMessage()), started);
        }
    }

    /** Start the historical CA seed on a daemon thread, walking month-sized chunks. */
    public boolean startSeed(LocalDate from, LocalDate to) {
        if (!seedRunning.compareAndSet(false, true)) return false;
        stopRequested = false;
        seedStatus = "running";
        chunksDone = 0;
        rowsSaved = 0;
        Thread worker = new Thread(() -> {
            try {
                for (LocalDate start = from; !start.isAfter(to); start = start.plusMonths(1)) {
                    if (stopRequested) { seedStatus = "stopped at " + start; return; }
                    LocalDate end = start.plusMonths(1).minusDays(1);
                    if (end.isAfter(to)) end = to;
                    IngestResult r = ingestRange(start, end, false);
                    chunksDone++;
                    rowsSaved += r.rows();
                    sleep();
                }
                seedStatus = "recomputing adjustments";
                priceAdjustmentService.recomputeAll();
                seedStatus = "completed";
            } catch (RuntimeException e) {
                seedStatus = "failed: " + truncate(e.getMessage());
                log.error("[CorpAction] seed failed: {}", e.getMessage());
            } finally {
                seedRunning.set(false);
            }
        }, "corpaction-seeder");
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

    private Long resolveInstrument(String isin) {
        if (!cacheLoaded) {
            for (Instrument inst : instrumentRepo.findAll()) {
                isinToInstrumentId.put(inst.getIsin(), inst.getId());
            }
            cacheLoaded = true;
        }
        return isinToInstrumentId.computeIfAbsent(isin,
                k -> instrumentRepo.findByIsin(k).map(Instrument::getId).orElse(null));
    }

    private IngestResult ledger(LocalDate businessDate, IngestionRun.Status status,
                                int rows, String message, LocalDateTime started) {
        IngestionRun run = runRepo
                .findByJobNameAndBusinessDate(IngestionRun.JOB_CORPORATE_ACTION, businessDate)
                .orElseGet(() -> IngestionRun.builder()
                        .jobName(IngestionRun.JOB_CORPORATE_ACTION).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
        return new IngestResult(status, rows);
    }

    private static String dedupHash(CaRecord r) {
        String key = r.isin() + "|" + r.exDate() + "|" + r.subject();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(THROTTLE_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
