package org.amit.finwise.cfo.service.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.model.MarketEvent;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.NewsCluster;
import org.amit.finwise.cfo.repository.ArticleEntityRepository;
import org.amit.finwise.cfo.repository.EventOutcomeRepository;
import org.amit.finwise.cfo.repository.MarketEventRepository;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.NewsClusterRepository;
import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Outcome enrichment (DF-6, pipeline step 3): nightly, after the bhavcopy lands,
 * join every recently-opened event cluster to the realized excess returns of the
 * instruments it mentioned, and persist them as {@link EventOutcome} rows.
 *
 * The baseline is the first trading day on/after the cluster's first-seen date;
 * the horizon is N trading days later (1/5/20). Returns use the back-adjusted
 * close, and excess is over the benchmark (Nifty) for the same window — so a
 * stock that rose only because the whole market rose scores ~0, not a false
 * positive. Idempotent: existing (cluster, instrument, horizon) rows are skipped,
 * and horizons that have not yet matured are simply re-attempted next night.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventOutcomeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Window of clusters to consider: old enough for 1d, young enough that 20d may still fill. */
    private static final int MIN_AGE_DAYS = 1;
    private static final int MAX_AGE_DAYS = 60;
    /** Calendar span pulled to cover ~20 trading days of horizon plus weekends/holidays. */
    private static final int PRICE_LOOKAHEAD_DAYS = 45;

    private final NewsClusterRepository clusterRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final ArticleEntityRepository articleEntityRepository;
    private final MarketEventRepository marketEventRepository;
    private final EventOutcomeRepository eventOutcomeRepository;
    private final InstrumentRepository instrumentRepository;
    private final EodPriceRepository eodPriceRepository;
    private final IndexEodRepository indexEodRepository;
    private final IngestionRunRepository runRepository;

    @Value("${cfo.rag.benchmark-index:Nifty 50}")
    private String benchmarkIndex;

    public record EnrichResult(int clustersScanned, int outcomesWritten) {}

    /**
     * Score all clusters in the maturity window. Records one ingestion-ledger row
     * for the run so the data-quality dashboard tracks freshness.
     */
    public EnrichResult enrichOutcomes() {
        LocalDateTime started = LocalDateTime.now();
        LocalDate today = LocalDate.now(IST);
        LocalDateTime from = today.minusDays(MAX_AGE_DAYS).atStartOfDay();
        LocalDateTime to = today.minusDays(MIN_AGE_DAYS).atTime(23, 59, 59);

        List<NewsCluster> clusters = clusterRepository.findOpenedBetween(from, to);
        int written = 0;
        for (NewsCluster cluster : clusters) {
            try {
                written += scoreCluster(cluster);
            } catch (RuntimeException e) {
                log.warn("Outcome scoring failed for cluster {}: {}", cluster.getId(), e.getMessage());
            }
        }

        IngestionRun.Status status = clusters.isEmpty()
                ? IngestionRun.Status.NO_DATA : IngestionRun.Status.SUCCESS;
        ledger(today, status, written,
                "clusters=" + clusters.size() + " outcomes=" + written, started);
        log.info("[RAG] Outcome enrichment: {} clusters scanned, {} outcomes written",
                clusters.size(), written);
        return new EnrichResult(clusters.size(), written);
    }

    private int scoreCluster(NewsCluster cluster) {
        LocalDate eventDate = cluster.getFirstSeen().toLocalDate();
        ensureMarketEvent(cluster, eventDate);

        List<String> symbols =
                articleEntityRepository.findInstrumentSymbolsForCluster(cluster.getId());
        if (symbols.isEmpty()) return 0;

        int written = 0;
        for (String symbol : symbols) {
            Optional<Instrument> instrument = instrumentRepository.findBySymbol(symbol);
            if (instrument.isEmpty()) continue;
            written += scoreInstrument(cluster, eventDate, instrument.get());
        }
        return written;
    }

    private int scoreInstrument(NewsCluster cluster, LocalDate eventDate, Instrument instrument) {
        List<EodPrice> series = eodPriceRepository
                .findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(
                        instrument.getId(), eventDate, eventDate.plusDays(PRICE_LOOKAHEAD_DAYS));
        if (series.size() < 2) return 0;

        EodPrice base = series.get(0);
        double basePrice = priceOf(base);
        if (basePrice <= 0) return 0;

        int written = 0;
        for (EventOutcome.Horizon h : EventOutcome.Horizon.values()) {
            if (series.size() <= h.tradingDays) continue;   // horizon not matured yet
            if (eventOutcomeRepository.findByClusterIdAndInstrumentIdAndHorizon(
                    cluster.getId(), instrument.getId(), h).isPresent()) {
                continue;                                    // idempotent
            }
            EodPrice horizonRow = series.get(h.tradingDays);
            double rawReturn = (priceOf(horizonRow) - basePrice) / basePrice;
            Double benchReturn = benchmarkReturn(base.getTradeDate(), horizonRow.getTradeDate());
            Double excess = benchReturn == null ? null : rawReturn - benchReturn;

            eventOutcomeRepository.save(EventOutcome.builder()
                    .clusterId(cluster.getId())
                    .instrumentId(instrument.getId())
                    .symbol(instrument.getSymbol())
                    .horizon(h)
                    .eventDate(eventDate)
                    .rawReturn(rawReturn)
                    .excessReturnVsNifty(excess)
                    .computedAt(LocalDateTime.now())
                    .build());
            written++;
        }
        return written;
    }

    /** Benchmark return between two trade dates, or null if the index series is missing. */
    private Double benchmarkReturn(LocalDate from, LocalDate to) {
        List<IndexEod> rows = indexEodRepository
                .findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(benchmarkIndex, from, to);
        if (rows.size() < 2) return null;
        BigDecimal first = rows.get(0).getClose();
        BigDecimal last = rows.get(rows.size() - 1).getClose();
        if (first == null || last == null || first.signum() == 0) return null;
        return last.subtract(first).doubleValue() / first.doubleValue();
    }

    /** Persist a structured MarketEvent for the cluster on first scoring (idempotent). */
    private void ensureMarketEvent(NewsCluster cluster, LocalDate eventDate) {
        if (marketEventRepository.findByClusterId(cluster.getId()).isPresent()) return;
        NewsArticle canonical = newsArticleRepository
                .findById(cluster.getCanonicalArticleId()).orElse(null);
        NewsArticle.Category category = canonical != null ? canonical.getCategory() : null;
        try {
            marketEventRepository.save(MarketEvent.builder()
                    .clusterId(cluster.getId())
                    .eventType(mapEventType(category, canonical))
                    .eventDate(eventDate)
                    .structuredPayload(payload(canonical))
                    .build());
        } catch (RuntimeException e) {
            // Raced the unique(cluster_id) — another run created it first.
            log.debug("MarketEvent already present for cluster {}", cluster.getId());
        }
    }

    private MarketEvent.EventType mapEventType(NewsArticle.Category category, NewsArticle a) {
        if (category == null) return MarketEvent.EventType.OTHER;
        return switch (category) {
            case EARNINGS -> MarketEvent.EventType.RESULTS;
            case IPO -> MarketEvent.EventType.IPO;
            case POLICY -> isRateStory(a) ? MarketEvent.EventType.RATE_DECISION
                                          : MarketEvent.EventType.REGULATORY;
            case REGULATORY -> MarketEvent.EventType.REGULATORY;
            case CORPORATE_ACTION, CORPORATE -> MarketEvent.EventType.MNA;
            case ANALYST_RECOMMENDATION -> MarketEvent.EventType.GUIDANCE;
            case MACRO, MACRO_RISK, ECONOMY -> MarketEvent.EventType.MACRO_PRINT;
            default -> MarketEvent.EventType.OTHER;
        };
    }

    private boolean isRateStory(NewsArticle a) {
        if (a == null || a.getTitle() == null) return false;
        String t = a.getTitle().toLowerCase();
        return t.contains("repo") || t.contains("rate cut") || t.contains("rate hike")
                || t.contains("monetary policy") || t.contains("rbi");
    }

    private String payload(NewsArticle a) {
        if (a == null) return null;
        String cat = a.getCategory() != null ? a.getCategory().name() : "null";
        String sent = a.getSentiment() != null ? a.getSentiment().name() : "null";
        return "{\"category\":\"" + cat + "\",\"sentiment\":\"" + sent + "\"}";
    }

    private double priceOf(EodPrice p) {
        BigDecimal v = p.getAdjClose() != null ? p.getAdjClose() : p.getClose();
        return v != null ? v.doubleValue() : 0.0;
    }

    private void ledger(LocalDate businessDate, IngestionRun.Status status, int rows,
                        String message, LocalDateTime started) {
        IngestionRun run = runRepository
                .findByJobNameAndBusinessDate(IngestionRun.JOB_NEWS_OUTCOME, businessDate)
                .orElseGet(() -> IngestionRun.builder()
                        .jobName(IngestionRun.JOB_NEWS_OUTCOME).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
    }
}
