package org.amit.finwise.marketdata.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.client.NseArchiveClient;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.parser.BhavcopyParsers;
import org.amit.finwise.marketdata.parser.BhavcopyParsers.EodRecord;
import org.amit.finwise.marketdata.parser.BhavcopyParsers.IndexRecord;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ingests one business date of NSE cash-market data: the full-market bhavcopy
 * into md_eod_price (+ md_instrument upserts) and the all-indices close file
 * into md_index_eod.
 *
 * Writes go through JDBC batch upserts (ON CONFLICT DO UPDATE) — ~2,000 rows
 * per date, ~3M rows for a 6-year seed — and every attempt is recorded in
 * md_ingestion_run, which makes re-runs idempotent and gap repair cheap.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EodIngestionService {

    /** UDiFF replaced the old cm format in early Jul-2024; near the cutover we try both. */
    static final LocalDate UDIFF_CUTOVER = LocalDate.of(2024, 7, 1);

    private static final int BATCH_SIZE = 1000;

    private static final String EOD_UPSERT = """
            INSERT INTO md_eod_price
              (instrument_id, trade_date, symbol, open, high, low, close, last, prev_close,
               volume, turnover, trades, series, source, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
              symbol = EXCLUDED.symbol, open = EXCLUDED.open, high = EXCLUDED.high,
              low = EXCLUDED.low, close = EXCLUDED.close, last = EXCLUDED.last,
              prev_close = EXCLUDED.prev_close, volume = EXCLUDED.volume,
              turnover = EXCLUDED.turnover, trades = EXCLUDED.trades,
              series = EXCLUDED.series, source = EXCLUDED.source
            """;

    private static final String DELIVERY_UPDATE = """
            UPDATE md_eod_price SET vwap = ?, deliv_qty = ?, deliv_pct = ?
            WHERE symbol = ? AND trade_date = ?
            """;

    private static final String INDEX_UPSERT = """
            INSERT INTO md_index_eod
              (index_name, trade_date, open, high, low, close, points_change, change_pct,
               volume, turnover_cr, pe, pb, div_yield, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (index_name, trade_date) DO UPDATE SET
              open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low,
              close = EXCLUDED.close, points_change = EXCLUDED.points_change,
              change_pct = EXCLUDED.change_pct, volume = EXCLUDED.volume,
              turnover_cr = EXCLUDED.turnover_cr, pe = EXCLUDED.pe, pb = EXCLUDED.pb,
              div_yield = EXCLUDED.div_yield
            """;

    private final NseArchiveClient archiveClient;
    private final InstrumentRepository instrumentRepo;
    private final IngestionRunRepository runRepo;
    private final JdbcTemplate jdbc;

    /** isin → cached identity; populated lazily, shared by seeder and daily job. */
    private final Map<String, CachedInstrument> instrumentCache = new HashMap<>();
    private boolean cacheLoaded = false;

    private record CachedInstrument(Long id, String symbol, String series, boolean hasName) {}

    public record IngestResult(IngestionRun.Status status, int rows, boolean skipped) {}

    /**
     * Ingest the cash-market bhavcopy for one date. With force=false, dates
     * already recorded as SUCCESS or NO_DATA are skipped without a network call.
     */
    public synchronized IngestResult ingestBhavcopy(LocalDate date, boolean force) {
        Optional<IngestionRun> existing =
                runRepo.findByJobNameAndBusinessDate(IngestionRun.JOB_BHAVCOPY, date);
        if (!force && existing.isPresent()
                && existing.get().getStatus() != IngestionRun.Status.FAILED) {
            return new IngestResult(existing.get().getStatus(), 0, true);
        }

        LocalDateTime started = LocalDateTime.now();
        try {
            String source;
            Optional<String> csv;
            if (date.isBefore(UDIFF_CUTOVER)) {
                source = "bhavcopy_cm";
                csv = archiveClient.fetchCmBhavcopyOld(date);
                if (csv.isEmpty()) {
                    source = "bhavcopy_udiff";
                    csv = archiveClient.fetchCmBhavcopyUdiff(date);
                }
            } else {
                source = "bhavcopy_udiff";
                csv = archiveClient.fetchCmBhavcopyUdiff(date);
                if (csv.isEmpty()) {
                    source = "bhavcopy_cm";
                    csv = archiveClient.fetchCmBhavcopyOld(date);
                }
            }

            if (csv.isEmpty()) {
                return record(IngestionRun.JOB_BHAVCOPY, date, IngestionRun.Status.NO_DATA,
                        0, "no file (holiday/weekend)", started);
            }

            List<EodRecord> records = source.equals("bhavcopy_cm")
                    ? BhavcopyParsers.parseOldCm(csv.get())
                    : BhavcopyParsers.parseUdiff(csv.get());
            if (records.isEmpty()) {
                return record(IngestionRun.JOB_BHAVCOPY, date, IngestionRun.Status.FAILED,
                        0, "file fetched but 0 EQ/BE rows parsed (" + source + ")", started);
            }

            Map<String, Long> isinToId = resolveInstruments(records);
            final String src = source;
            List<Object[]> args = records.stream()
                    .map(r -> new Object[]{
                            isinToId.get(r.isin()), r.tradeDate(), r.symbol(), r.open(), r.high(),
                            r.low(), r.close(), r.last(), r.prevClose(), r.volume(), r.turnover(),
                            r.trades(), r.series(), src})
                    .toList();
            batchUpsert(EOD_UPSERT, args);

            return record(IngestionRun.JOB_BHAVCOPY, date, IngestionRun.Status.SUCCESS,
                    records.size(), src, started);
        } catch (RuntimeException e) {
            log.error("[MarketData] bhavcopy ingestion failed for {}: {}", date, e.getMessage());
            return record(IngestionRun.JOB_BHAVCOPY, date, IngestionRun.Status.FAILED,
                    0, truncate(e.getMessage()), started);
        }
    }

    /** Ingest the all-indices close file (with P/E, P/B, div yield) for one date. */
    public synchronized IngestResult ingestIndexCloseAll(LocalDate date, boolean force) {
        Optional<IngestionRun> existing =
                runRepo.findByJobNameAndBusinessDate(IngestionRun.JOB_INDEX_CLOSE, date);
        if (!force && existing.isPresent()
                && existing.get().getStatus() != IngestionRun.Status.FAILED) {
            return new IngestResult(existing.get().getStatus(), 0, true);
        }

        LocalDateTime started = LocalDateTime.now();
        try {
            Optional<String> csv = archiveClient.fetchIndexCloseAll(date);
            if (csv.isEmpty()) {
                return record(IngestionRun.JOB_INDEX_CLOSE, date, IngestionRun.Status.NO_DATA,
                        0, "no file (holiday/weekend)", started);
            }
            List<IndexRecord> records = BhavcopyParsers.parseIndexCloseAll(csv.get());
            if (records.isEmpty()) {
                return record(IngestionRun.JOB_INDEX_CLOSE, date, IngestionRun.Status.FAILED,
                        0, "file fetched but 0 index rows parsed", started);
            }
            List<Object[]> args = records.stream()
                    .map(r -> new Object[]{
                            r.indexName(), r.tradeDate(), r.open(), r.high(), r.low(), r.close(),
                            r.pointsChange(), r.changePct(), r.volume(), r.turnoverCr(),
                            r.pe(), r.pb(), r.divYield()})
                    .toList();
            batchUpsert(INDEX_UPSERT, args);

            return record(IngestionRun.JOB_INDEX_CLOSE, date, IngestionRun.Status.SUCCESS,
                    records.size(), null, started);
        } catch (RuntimeException e) {
            log.error("[MarketData] index-close ingestion failed for {}: {}", date, e.getMessage());
            return record(IngestionRun.JOB_INDEX_CLOSE, date, IngestionRun.Status.FAILED,
                    0, truncate(e.getMessage()), started);
        }
    }

    /**
     * Enrich existing md_eod_price rows for one date with VWAP and delivery
     * quantity/percentage from sec_bhavdata_full, joined by symbol + date.
     * Requires the bhavcopy for that date to be ingested first — rows that
     * don't exist yet simply wouldn't match, so we gate on bhavcopy SUCCESS.
     */
    public synchronized IngestResult ingestDelivery(LocalDate date, boolean force) {
        Optional<IngestionRun> existing =
                runRepo.findByJobNameAndBusinessDate(IngestionRun.JOB_DELIVERY, date);
        if (!force && existing.isPresent()
                && existing.get().getStatus() != IngestionRun.Status.FAILED) {
            return new IngestResult(existing.get().getStatus(), 0, true);
        }

        LocalDateTime started = LocalDateTime.now();
        IngestionRun.Status bhavStatus = runRepo
                .findByJobNameAndBusinessDate(IngestionRun.JOB_BHAVCOPY, date)
                .map(IngestionRun::getStatus)
                .orElse(null);
        if (bhavStatus == IngestionRun.Status.NO_DATA) {
            // holiday/weekend — no trading day, so no delivery data either
            return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.NO_DATA,
                    0, "no trading day (bhavcopy NO_DATA)", started);
        }
        if (bhavStatus != IngestionRun.Status.SUCCESS) {
            return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.FAILED,
                    0, "bhavcopy not ingested for this date yet", started);
        }

        try {
            Optional<String> csv = archiveClient.fetchSecBhavdataFull(date);
            if (csv.isEmpty()) {
                return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.NO_DATA,
                        0, "no file (holiday/weekend)", started);
            }
            List<BhavcopyParsers.DeliveryRecord> records =
                    BhavcopyParsers.parseSecBhavdataFull(csv.get());
            if (records.isEmpty()) {
                return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.FAILED,
                        0, "file fetched but 0 EQ/BE rows parsed", started);
            }
            List<Object[]> args = records.stream()
                    .map(r -> new Object[]{
                            r.vwap(), r.delivQty(), r.delivPct(), r.symbol(), r.tradeDate()})
                    .toList();
            int matched = 0;
            for (int from = 0; from < args.size(); from += BATCH_SIZE) {
                int[] counts = jdbc.batchUpdate(DELIVERY_UPDATE,
                        args.subList(from, Math.min(from + BATCH_SIZE, args.size())));
                for (int c : counts) if (c > 0) matched += c;
            }
            return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.SUCCESS,
                    matched, records.size() + " parsed, " + matched + " matched", started);
        } catch (RuntimeException e) {
            log.error("[MarketData] delivery ingestion failed for {}: {}", date, e.getMessage());
            return record(IngestionRun.JOB_DELIVERY, date, IngestionRun.Status.FAILED,
                    0, truncate(e.getMessage()), started);
        }
    }

    /**
     * Upserts instruments for all records and returns isin → id. The latest
     * sighting wins for symbol/series; name is filled once UDiFF provides it.
     */
    private Map<String, Long> resolveInstruments(List<EodRecord> records) {
        if (!cacheLoaded) {
            for (Instrument inst : instrumentRepo.findAll()) {
                instrumentCache.put(inst.getIsin(), new CachedInstrument(
                        inst.getId(), inst.getSymbol(), inst.getSeries(), inst.getName() != null));
            }
            cacheLoaded = true;
        }

        Map<String, Long> result = new HashMap<>();
        for (EodRecord r : records) {
            CachedInstrument cached = instrumentCache.get(r.isin());
            if (cached == null) {
                Instrument saved = instrumentRepo.save(Instrument.builder()
                        .isin(r.isin()).symbol(r.symbol()).series(r.series()).name(r.name())
                        .build());
                cached = new CachedInstrument(saved.getId(), saved.getSymbol(),
                        saved.getSeries(), saved.getName() != null);
                instrumentCache.put(r.isin(), cached);
            } else if (!cached.symbol().equals(r.symbol())
                    || !java.util.Objects.equals(cached.series(), r.series())
                    || (!cached.hasName() && r.name() != null)) {
                Instrument inst = instrumentRepo.findById(cached.id()).orElseThrow();
                inst.setSymbol(r.symbol());
                inst.setSeries(r.series());
                if (r.name() != null) inst.setName(r.name());
                instrumentRepo.save(inst);
                cached = new CachedInstrument(inst.getId(), inst.getSymbol(),
                        inst.getSeries(), inst.getName() != null);
                instrumentCache.put(r.isin(), cached);
            }
            result.put(r.isin(), cached.id());
        }
        return result;
    }

    private void batchUpsert(String sql, List<Object[]> args) {
        for (int from = 0; from < args.size(); from += BATCH_SIZE) {
            jdbc.batchUpdate(sql, args.subList(from, Math.min(from + BATCH_SIZE, args.size())));
        }
    }

    private IngestResult record(String job, LocalDate date, IngestionRun.Status status,
                                int rows, String message, LocalDateTime started) {
        IngestionRun run = runRepo.findByJobNameAndBusinessDate(job, date)
                .orElseGet(() -> IngestionRun.builder().jobName(job).businessDate(date).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepo.save(run);
        return new IngestResult(status, rows, false);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
