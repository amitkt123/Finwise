package org.amit.finwise.marketdata.ops;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.repository.ArticleEntityRepository;
import org.amit.finwise.cfo.repository.EventOutcomeRepository;
import org.amit.finwise.cfo.repository.NewsClusterRepository;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.repository.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The ops dashboard behind {@code GET /api/data-quality} (DF-5): per-dataset
 * freshness, row counts, last success and open gaps, plus the macro-series SLA
 * view. One place to answer "is the data layer healthy right now?".
 */
@Service
@RequiredArgsConstructor
public class DataQualityService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final IngestionRunRepository runRepo;
    private final GapRepairService gapRepairService;
    private final MacroSeriesService macroSeriesService;

    private final InstrumentRepository instrumentRepo;
    private final EodPriceRepository eodRepo;
    private final IndexEodRepository indexEodRepo;
    private final CorporateActionRepository corporateActionRepo;
    private final CorporateEventRepository corporateEventRepo;
    private final MfSchemeRepository mfSchemeRepo;
    private final MfNavRepository mfNavRepo;
    private final AnnouncementRepository announcementRepo;
    private final ShareholdingPatternRepository shareholdingRepo;
    private final MarketDealRepository dealRepo;
    private final NewsClusterRepository newsClusterRepo;
    private final ArticleEntityRepository articleEntityRepo;
    private final EventOutcomeRepository eventOutcomeRepo;

    public Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", java.time.LocalDateTime.now(IST));
        out.put("jobs", jobsView());
        out.put("datasets", datasetsView());
        out.put("openGaps", gapRepairService.openGaps());
        out.put("macroSeries", macroSeriesService.freshnessView());
        return out;
    }

    /** Per-job: last success, freshness, and status breakdown from the ledger. */
    private Map<String, Object> jobsView() {
        LocalDate today = LocalDate.now(IST);
        Map<String, Object> jobs = new LinkedHashMap<>();
        for (String job : IngestionRun.ALL_JOBS) {
            Map<String, Object> row = new LinkedHashMap<>();
            Optional<IngestionRun> lastSuccess =
                    runRepo.findTopByJobNameAndStatusOrderByBusinessDateDesc(
                            job, IngestionRun.Status.SUCCESS);
            Optional<IngestionRun> lastRun = runRepo.findTopByJobNameOrderByBusinessDateDesc(job);
            row.put("lastSuccessDate", lastSuccess.map(IngestionRun::getBusinessDate).orElse(null));
            row.put("daysSinceSuccess", lastSuccess
                    .map(r -> ChronoUnit.DAYS.between(r.getBusinessDate(), today)).orElse(null));
            row.put("lastRunStatus", lastRun.map(r -> r.getStatus().name()).orElse("NEVER_RUN"));
            row.put("lastRunMessage", lastRun.map(IngestionRun::getMessage).orElse(null));
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (IngestionRun.Status s : IngestionRun.Status.values()) {
                byStatus.put(s.name(), runRepo.countByJobNameAndStatus(job, s));
            }
            row.put("byStatus", byStatus);
            jobs.put(job, row);
        }
        return jobs;
    }

    /** Per-table row counts and coverage span. */
    private Map<String, Object> datasetsView() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instruments", instrumentRepo.count());
        Map<String, Object> eod = new LinkedHashMap<>();
        eod.put("rows", eodRepo.count());
        eod.put("minDate", eodRepo.findMinTradeDate().orElse(null));
        eod.put("maxDate", eodRepo.findMaxTradeDate().orElse(null));
        d.put("eodPrice", eod);
        d.put("indexEod", indexEodRepo.count());
        d.put("corporateActions", corporateActionRepo.count());
        d.put("corporateEvents", corporateEventRepo.count());
        d.put("mfSchemes", mfSchemeRepo.count());
        d.put("mfNavRows", mfNavRepo.count());
        d.put("announcements", announcementRepo.count());
        d.put("shareholdingPatterns", shareholdingRepo.count());
        Map<String, Object> deals = new LinkedHashMap<>();
        deals.put("bulk", dealRepo.countByDealType(
                org.amit.finwise.marketdata.model.MarketDeal.DealType.BULK));
        deals.put("block", dealRepo.countByDealType(
                org.amit.finwise.marketdata.model.MarketDeal.DealType.BLOCK));
        d.put("deals", deals);

        // DF-6 — outcome-linked news RAG
        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("newsClusters", newsClusterRepo.count());
        rag.put("articleEntities", articleEntityRepo.count());
        rag.put("eventOutcomes", eventOutcomeRepo.count());
        d.put("newsRag", rag);
        return d;
    }
}
