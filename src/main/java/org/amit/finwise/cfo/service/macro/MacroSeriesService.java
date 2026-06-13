package org.amit.finwise.cfo.service.macro;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeries;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.amit.finwise.cfo.repository.MacroSeriesRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns the {@code cfo_macro_series} table (DF-3): drives the FBIL / RBI / MOSPI
 * providers and DF-1's index feed into it, and exposes typed read helpers plus a
 * per-series freshness view that replaces the old "update the config" nags.
 *
 * <p>Cadence (wired from {@code CFOScheduler}):
 *   <ul>
 *     <li>{@link #ingestDaily()} — FBIL curve + USD/INR, RBI policy rates, and
 *         India VIX from {@code md_index_eod}; run each evening.</li>
 *     <li>{@link #ingestMonthly()} — MOSPI CPI/IIP/WPI full history; a monthly
 *         pull that both seeds and refreshes.</li>
 *   </ul>
 *
 * <p>Writes are idempotent: one row per (code, obs_date), updated in place on
 * re-run. {@link MacroStateService} reads through {@link #latest} /
 * {@link #valueAsOf}, falling back to config only when a series has no rows yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MacroSeriesService {

    private final MacroSeriesRepository repo;
    private final FbilProvider fbilProvider;
    private final RbiPolicyRateProvider rbiProvider;
    private final MospiProvider mospiProvider;
    private final IndexEodRepository indexEodRepo;

    @Value("${cfo.macro.vix-index-name:India VIX}")
    private String vixIndexName;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record IngestResult(int observations, int seriesTouched) {}

    // ── Ingestion ───────────────────────────────────────────────────────────

    /** Daily pull: FBIL curve + USD/INR, RBI policy rates, India VIX. */
    public IngestResult ingestDaily() {
        LocalDate today = LocalDate.now(IST);
        List<MacroObservation> obs = new ArrayList<>();
        obs.addAll(fbilProvider.fetch(today));
        obs.addAll(rbiProvider.fetch(today));
        indiaVixFromIndexFile().ifPresent(obs::add);
        IngestResult r = upsert(obs);
        log.info("[MacroSeries] Daily ingest: {} observations across {} series",
                r.observations(), r.seriesTouched());
        return r;
    }

    /** Monthly pull: MOSPI CPI/IIP/WPI full history (seed + refresh in one call). */
    public IngestResult ingestMonthly() {
        IngestResult r = upsert(mospiProvider.fetch());
        log.info("[MacroSeries] Monthly ingest: {} observations across {} series",
                r.observations(), r.seriesTouched());
        return r;
    }

    /** India VIX from DF-1's index-close feed (md_index_eod), dated to its trade date. */
    private Optional<MacroObservation> indiaVixFromIndexFile() {
        return indexEodRepo.findTopByIndexNameIgnoreCaseOrderByTradeDateDesc(vixIndexName)
                .filter(e -> e.getClose() != null
                        && MacroSeriesCode.INDIA_VIX.isSane(e.getClose().doubleValue()))
                .map(e -> MacroObservation.of(
                        MacroSeriesCode.INDIA_VIX, e.getTradeDate(), e.getClose(), "NSE_INDEX"));
    }

    /** Idempotent upsert: one row per (code, obs_date), value/source refreshed on re-run. */
    public IngestResult upsert(List<MacroObservation> observations) {
        if (observations.isEmpty()) return new IngestResult(0, 0);

        Map<MacroSeriesCode, Map<LocalDate, MacroSeries>> existingByCode = new HashMap<>();
        Map<MacroSeriesCode, Boolean> touched = new HashMap<>();
        List<MacroSeries> toSave = new ArrayList<>();

        for (MacroObservation o : observations) {
            Map<LocalDate, MacroSeries> existing = existingByCode.computeIfAbsent(o.code(),
                    code -> {
                        Map<LocalDate, MacroSeries> m = new HashMap<>();
                        for (MacroSeries s : repo.findBySeriesCodeOrderByObsDate(code)) {
                            m.put(s.getObsDate(), s);
                        }
                        return m;
                    });
            MacroSeries row = existing.get(o.obsDate());
            if (row == null) {
                row = MacroSeries.builder()
                        .seriesCode(o.code()).obsDate(o.obsDate())
                        .value(o.value()).source(o.source())
                        .build();
                existing.put(o.obsDate(), row);
            } else {
                row.setValue(o.value());
                row.setSource(o.source());
            }
            toSave.add(row);
            touched.put(o.code(), true);
        }
        repo.saveAll(toSave);
        return new IngestResult(toSave.size(), touched.size());
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** Most recent value for a series, or empty if never ingested. */
    public Optional<BigDecimal> latest(MacroSeriesCode code) {
        return repo.findTopBySeriesCodeOrderByObsDateDesc(code).map(MacroSeries::getValue);
    }

    /** Most recent observation (value + date) for a series. */
    public Optional<MacroSeries> latestObs(MacroSeriesCode code) {
        return repo.findTopBySeriesCodeOrderByObsDateDesc(code);
    }

    /** Value as of a date (most recent observation on or before it). */
    public Optional<BigDecimal> valueAsOf(MacroSeriesCode code, LocalDate asOf) {
        return repo.findTopBySeriesCodeAndObsDateLessThanEqualOrderByObsDateDesc(code, asOf)
                .map(MacroSeries::getValue);
    }

    /**
     * Per-series freshness against each code's SLA. {@code stale} means the most
     * recent observation is older than {@link MacroSeriesCode#slaMaxAgeDays()};
     * {@code missing} means the series has never been ingested. Drives the
     * data-quality view and the staleness notes on the macro snapshot.
     */
    public List<SeriesFreshness> freshness() {
        LocalDate today = LocalDate.now(IST);
        List<SeriesFreshness> out = new ArrayList<>();
        for (MacroSeriesCode code : MacroSeriesCode.values()) {
            Optional<MacroSeries> obs = repo.findTopBySeriesCodeOrderByObsDateDesc(code);
            if (obs.isEmpty()) {
                out.add(new SeriesFreshness(code, null, null, true, false));
                continue;
            }
            long age = ChronoUnit.DAYS.between(obs.get().getObsDate(), today);
            out.add(new SeriesFreshness(code, obs.get().getObsDate(), obs.get().getValue(),
                    false, age > code.slaMaxAgeDays()));
        }
        return out;
    }

    public record SeriesFreshness(MacroSeriesCode code, LocalDate latestObsDate, BigDecimal latestValue,
                                  boolean missing, boolean stale) {}

    /** Compact freshness map for the admin / data-quality endpoint. */
    public Map<String, Object> freshnessView() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (SeriesFreshness f : freshness()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("latestObsDate", f.latestObsDate());
            row.put("latestValue", f.latestValue());
            row.put("count", repo.countBySeriesCode(f.code()));
            row.put("slaMaxAgeDays", f.code().slaMaxAgeDays());
            row.put("status", f.missing() ? "MISSING" : f.stale() ? "STALE" : "FRESH");
            out.put(f.code().name(), row);
        }
        return out;
    }
}
