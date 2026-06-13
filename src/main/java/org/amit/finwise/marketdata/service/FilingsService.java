package org.amit.finwise.marketdata.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseApiClient;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.model.MarketDeal;
import org.amit.finwise.marketdata.parser.FilingsParsers;
import org.amit.finwise.marketdata.parser.FilingsParsers.AnnouncementRecord;
import org.amit.finwise.marketdata.parser.FilingsParsers.DealRecord;
import org.amit.finwise.marketdata.parser.FilingsParsers.ShareholdingRecord;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ingests the DF-4 NSE filings feeds — corporate announcements, quarterly
 * shareholding patterns, and bulk/block deals — into their tables, each
 * date-range pull ledgered in md_ingestion_run keyed by the chunk's end date.
 *
 * All three follow the {@code CorporateActionService} contract: fetch a range,
 * resolve instruments by symbol against the DF-1 master, JDBC batch-upsert with
 * a dedup hash (or natural key for shareholding), and ledger the attempt. Daily
 * jobs call the range methods over a short trailing window; seeds walk longer
 * ranges in chunks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilingsService {

    private static final int BATCH_SIZE = 500;

    private static final String ANN_UPSERT = """
            INSERT INTO md_announcement
              (instrument_id, symbol, company_name, subject, details, broadcast_at,
               attachment_url, dedup_hash, source, created_at)
            VALUES (?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (dedup_hash) DO UPDATE SET
              instrument_id = EXCLUDED.instrument_id, company_name = EXCLUDED.company_name,
              subject = EXCLUDED.subject, details = EXCLUDED.details,
              attachment_url = EXCLUDED.attachment_url
            """;

    private static final String SHP_UPSERT = """
            INSERT INTO md_shareholding_pattern
              (instrument_id, symbol, company_name, period_ended, promoter_pct,
               promoter_pledge_pct, fii_pct, dii_pct, public_pct, source, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (symbol, period_ended) DO UPDATE SET
              instrument_id = EXCLUDED.instrument_id, company_name = EXCLUDED.company_name,
              promoter_pct = EXCLUDED.promoter_pct, promoter_pledge_pct = EXCLUDED.promoter_pledge_pct,
              fii_pct = EXCLUDED.fii_pct, dii_pct = EXCLUDED.dii_pct, public_pct = EXCLUDED.public_pct
            """;

    private static final String DEAL_UPSERT = """
            INSERT INTO md_market_deal
              (instrument_id, deal_type, deal_date, symbol, security_name, client_name,
               side, quantity, price, dedup_hash, source, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (dedup_hash) DO UPDATE SET
              instrument_id = EXCLUDED.instrument_id, security_name = EXCLUDED.security_name,
              client_name = EXCLUDED.client_name, side = EXCLUDED.side,
              quantity = EXCLUDED.quantity, price = EXCLUDED.price
            """;

    private final NseApiClient nseApiClient;
    private final InstrumentRepository instrumentRepo;
    private final IngestionRunRepository runRepo;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final Map<String, Long> symbolToInstrumentId = new HashMap<>();
    private boolean cacheLoaded = false;

    public record IngestResult(IngestionRun.Status status, int rows) {}

    // ── Announcements ────────────────────────────────────────────────────────

    public synchronized IngestResult ingestAnnouncements(LocalDate from, LocalDate to) {
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> json = nseApiClient.fetchAnnouncements(from, to);
            if (json.isEmpty()) {
                return ledger(IngestionRun.JOB_ANNOUNCEMENT, to, IngestionRun.Status.NO_DATA, 0,
                        "empty response " + from + ".." + to, started);
            }
            List<AnnouncementRecord> records =
                    FilingsParsers.parseAnnouncements(json.get(), objectMapper);
            List<Object[]> args = new ArrayList<>(records.size());
            for (AnnouncementRecord r : records) {
                String hash = sha256(r.symbol() + "|" + r.broadcastAt() + "|" + r.subject());
                args.add(new Object[]{
                        resolveInstrument(r.symbol()), r.symbol(), r.companyName(), r.subject(),
                        r.details(), r.broadcastAt(), r.attachmentUrl(), hash, "nse_ann"});
            }
            batch(ANN_UPSERT, args);
            return ledger(IngestionRun.JOB_ANNOUNCEMENT, to, IngestionRun.Status.SUCCESS, args.size(),
                    args.size() + " announcements " + from + ".." + to, started);
        } catch (RuntimeException e) {
            log.error("[Filings] announcements {}..{} failed: {}", from, to, e.getMessage());
            return ledger(IngestionRun.JOB_ANNOUNCEMENT, to, IngestionRun.Status.FAILED, 0,
                    truncate(e.getMessage()), started);
        }
    }

    // ── Shareholding patterns ──────────────────────────────────────────────────

    public synchronized IngestResult ingestShareholding(LocalDate from, LocalDate to) {
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> json = nseApiClient.fetchShareholdingPatterns(from, to);
            if (json.isEmpty()) {
                return ledger(IngestionRun.JOB_SHAREHOLDING, to, IngestionRun.Status.NO_DATA, 0,
                        "empty response " + from + ".." + to, started);
            }
            List<ShareholdingRecord> records =
                    FilingsParsers.parseShareholding(json.get(), objectMapper);
            // De-dup within the batch on (symbol, period) so a single ON CONFLICT key wins.
            Map<String, Object[]> byKey = new java.util.LinkedHashMap<>();
            for (ShareholdingRecord r : records) {
                byKey.put(r.symbol() + "|" + r.periodEnded(), new Object[]{
                        resolveInstrument(r.symbol()), r.symbol(), r.companyName(), r.periodEnded(),
                        r.promoterPct(), r.promoterPledgePct(), r.fiiPct(), r.diiPct(),
                        r.publicPct(), "nse_shp"});
            }
            batch(SHP_UPSERT, new ArrayList<>(byKey.values()));
            return ledger(IngestionRun.JOB_SHAREHOLDING, to, IngestionRun.Status.SUCCESS, byKey.size(),
                    byKey.size() + " patterns " + from + ".." + to, started);
        } catch (RuntimeException e) {
            log.error("[Filings] shareholding {}..{} failed: {}", from, to, e.getMessage());
            return ledger(IngestionRun.JOB_SHAREHOLDING, to, IngestionRun.Status.FAILED, 0,
                    truncate(e.getMessage()), started);
        }
    }

    // ── Bulk / block deals ─────────────────────────────────────────────────────

    /** Ingests both bulk and block deals for the range; ledgered per type. */
    public synchronized List<IngestResult> ingestDeals(LocalDate from, LocalDate to) {
        return List.of(
                ingestDealsOfType(MarketDeal.DealType.BULK, IngestionRun.JOB_BULK_DEAL, from, to),
                ingestDealsOfType(MarketDeal.DealType.BLOCK, IngestionRun.JOB_BLOCK_DEAL, from, to));
    }

    private IngestResult ingestDealsOfType(MarketDeal.DealType type, String job,
                                           LocalDate from, LocalDate to) {
        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> json = type == MarketDeal.DealType.BULK
                    ? nseApiClient.fetchBulkDeals(from, to)
                    : nseApiClient.fetchBlockDeals(from, to);
            if (json.isEmpty()) {
                return ledger(job, to, IngestionRun.Status.NO_DATA, 0,
                        "empty response " + from + ".." + to, started);
            }
            List<DealRecord> records = FilingsParsers.parseDeals(json.get(), objectMapper);
            List<Object[]> args = new ArrayList<>(records.size());
            for (DealRecord r : records) {
                String hash = sha256(type + "|" + r.dealDate() + "|" + r.symbol() + "|"
                        + r.clientName() + "|" + r.side() + "|" + r.quantity() + "|" + r.price());
                args.add(new Object[]{
                        resolveInstrument(r.symbol()), type.name(), r.dealDate(), r.symbol(),
                        r.securityName(), r.clientName(),
                        r.side() == null ? null : r.side().name(),
                        r.quantity(), r.price(), hash, "nse_deal"});
            }
            batch(DEAL_UPSERT, args);
            return ledger(job, to, IngestionRun.Status.SUCCESS, args.size(),
                    args.size() + " " + type + " deals " + from + ".." + to, started);
        } catch (RuntimeException e) {
            log.error("[Filings] {} deals {}..{} failed: {}", type, from, to, e.getMessage());
            return ledger(job, to, IngestionRun.Status.FAILED, 0, truncate(e.getMessage()), started);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Long resolveInstrument(String symbol) {
        if (symbol == null) return null;
        if (!cacheLoaded) {
            for (Instrument inst : instrumentRepo.findAll()) {
                symbolToInstrumentId.putIfAbsent(inst.getSymbol(), inst.getId());
            }
            cacheLoaded = true;
        }
        return symbolToInstrumentId.computeIfAbsent(symbol.toUpperCase(),
                k -> instrumentRepo.findBySymbol(k).map(Instrument::getId).orElse(null));
    }

    private void batch(String sql, List<Object[]> args) {
        for (int i = 0; i < args.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, args.subList(i, Math.min(i + BATCH_SIZE, args.size())));
        }
    }

    private IngestResult ledger(String job, LocalDate businessDate, IngestionRun.Status status,
                                int rows, String message, LocalDateTime started) {
        IngestionRun run = runRepo.findByJobNameAndBusinessDate(job, businessDate)
                .orElseGet(() -> IngestionRun.builder().jobName(job).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
        return new IngestResult(status, rows);
    }

    private static String sha256(String key) {
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
