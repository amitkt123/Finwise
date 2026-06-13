package org.amit.finwise.marketdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.parser.CorporateEventParser;
import org.amit.finwise.marketdata.parser.CorporateEventParser.EventRecord;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds the market event calendar (md_corporate_event) from two sources:
 *   - the forward NSE event calendar (/api/event-calendar) — board meetings with
 *     a stated purpose, fetched daily and ledgered under JOB_EVENT_CALENDAR;
 *   - a one-off import of the historical earnings JSON (results/dividends/
 *     bonus/split with realized dates).
 *
 * Both land as event-shaped rows (type + date + JSONB payload) so DF-6's
 * market_event can union over the table without remodeling. Idempotent via a
 * dedup hash over (symbol, date, type, source).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorporateEventService {

    private static final int BATCH_SIZE = 500;
    private static final String SRC_CALENDAR = "event_calendar";
    private static final String SRC_EARNINGS = "earnings_history";

    private static final String UPSERT = """
            INSERT INTO md_corporate_event
              (instrument_id, symbol, company_name, event_type, event_date, description,
               payload, dedup_hash, source, created_at)
            VALUES (?,?,?,?,?,?, cast(? as jsonb), ?,?, now())
            ON CONFLICT (dedup_hash) DO UPDATE SET
              instrument_id = EXCLUDED.instrument_id, company_name = EXCLUDED.company_name,
              event_type = EXCLUDED.event_type, description = EXCLUDED.description,
              payload = EXCLUDED.payload
            """;

    private final NseApiClient nseApiClient;
    private final InstrumentRepository instrumentRepo;
    private final IngestionRunRepository runRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> symbolToInstrumentId = new HashMap<>();
    private boolean cacheLoaded = false;

    public record ImportResult(IngestionRun.Status status, int rows, String message) {}

    /** Fetch the forward NSE event calendar and upsert it. Ledgered under JOB_EVENT_CALENDAR. */
    public synchronized ImportResult ingestEventCalendar() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> json = nseApiClient.fetchEventCalendar();
            if (json.isEmpty()) {
                return ledger(today, IngestionRun.Status.NO_DATA, 0, "empty calendar", started);
            }
            List<EventRecord> records =
                    CorporateEventParser.parseEventCalendar(json.get(), objectMapper);
            int saved = upsert(records, SRC_CALENDAR);
            return ledger(today, IngestionRun.Status.SUCCESS, saved, saved + " events", started);
        } catch (RuntimeException e) {
            log.error("[CorpEvent] event-calendar ingest failed: {}", e.getMessage());
            return ledger(today, IngestionRun.Status.FAILED, 0, truncate(e.getMessage()), started);
        }
    }

    /** One-off import of the historical earnings JSON file. Not ledgered (manual). */
    public ImportResult importEarningsHistory(Path jsonFile) {
        try {
            String json = Files.readString(jsonFile);
            List<EventRecord> records =
                    CorporateEventParser.parseEarningsHistory(json, objectMapper);
            int saved = upsert(records, SRC_EARNINGS);
            log.info("[CorpEvent] Imported {} historical events from {}", saved, jsonFile);
            return new ImportResult(IngestionRun.Status.SUCCESS, saved, saved + " imported");
        } catch (Exception e) {
            log.error("[CorpEvent] earnings import failed: {}", e.getMessage());
            return new ImportResult(IngestionRun.Status.FAILED, 0, truncate(e.getMessage()));
        }
    }

    private int upsert(List<EventRecord> records, String source) {
        List<Object[]> args = new ArrayList<>(records.size());
        for (EventRecord r : records) {
            args.add(new Object[]{
                    resolveInstrument(r.symbol()), r.symbol(), r.companyName(),
                    r.eventType().name(), r.eventDate(), r.description(),
                    r.payload(), dedupHash(r, source), source});
        }
        for (int i = 0; i < args.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(UPSERT, args.subList(i, Math.min(i + BATCH_SIZE, args.size())));
        }
        return args.size();
    }

    private Long resolveInstrument(String symbol) {
        if (!cacheLoaded) {
            for (Instrument inst : instrumentRepo.findAll()) {
                symbolToInstrumentId.put(inst.getSymbol(), inst.getId());
            }
            cacheLoaded = true;
        }
        return symbolToInstrumentId.computeIfAbsent(symbol,
                k -> instrumentRepo.findBySymbol(k).map(Instrument::getId).orElse(null));
    }

    private ImportResult ledger(LocalDate businessDate, IngestionRun.Status status,
                                int rows, String message, LocalDateTime started) {
        IngestionRun run = runRepo
                .findByJobNameAndBusinessDate(IngestionRun.JOB_EVENT_CALENDAR, businessDate)
                .orElseGet(() -> IngestionRun.builder()
                        .jobName(IngestionRun.JOB_EVENT_CALENDAR).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
        return new ImportResult(status, rows, message);
    }

    private static String dedupHash(EventRecord r, String source) {
        String key = r.symbol() + "|" + r.eventDate() + "|" + r.eventType() + "|" + source;
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

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
