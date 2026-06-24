package org.amit.finwise.company.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.analytics.BackbonePriceReader;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.cfo.service.research.EventStudyService;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.company.model.CompanyProfile;
import org.amit.finwise.marketdata.model.CorporateAction;
import org.amit.finwise.marketdata.model.CorporateEvent;
import org.amit.finwise.marketdata.model.CorporateEventType;
import org.amit.finwise.marketdata.model.MarketDeal;
import org.amit.finwise.marketdata.model.ShareholdingPattern;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.CorporateEventRepository;
import org.amit.finwise.marketdata.repository.MarketDealRepository;
import org.amit.finwise.marketdata.repository.ShareholdingPatternRepository;
import org.amit.finwise.policy.model.PolicyEventCard;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Phase 1 aggregator behind {@code GET /api/company/{symbol}}.
 *
 * It leans on {@link StockIntelligenceService} for the heavy analytics
 * ({@link StockDeepDive}: fundamentals, risk, portfolio fit, scorecard, news) and
 * joins the {@code marketdata} corporate-action / ownership / deal tables plus a
 * thin relative-strength and event-study layer to produce a six-card profile.
 * No new persistence — pure wiring over already-stored data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private final StockIntelligenceService stockIntelligenceService;
    private final BackbonePriceReader backbonePriceReader;
    private final FactorProperties factorProperties;
    private final SymbolExtractorService symbolExtractorService;
    private final CorporateActionRepository corporateActionRepository;
    private final CorporateEventRepository corporateEventRepository;
    private final ShareholdingPatternRepository shareholdingPatternRepository;
    private final MarketDealRepository marketDealRepository;
    private final EventStudyService eventStudyService;
    private final PolicyIntelligenceService policyIntelligenceService;
    private final CompanyBeginnerNarrationService beginnerNarrationService;

    private static final int PRICE_LOOKBACK_DAYS = 420;
    private static final int MAX_PAST_ACTIONS = 10;
    private static final int MAX_FORWARD_EVENTS = 10;
    private static final int MAX_DEALS = 8;
    private static final int MAX_RESULTS_EVENTS = 12;
    private static final int EVENT_STUDY_WINDOW = 3;
    private static final int POLICY_LIMIT = 6;

    public CompanyProfile getProfile(String symbol, String userId) {
        String sym = symbol.toUpperCase().trim();
        List<String> dataGaps = new ArrayList<>();

        StockDeepDive deepDive = stockIntelligenceService.analyze(sym, userId);
        dataGaps.addAll(deepDive.dataGaps());

        String sector = resolveSector(sym, deepDive);

        // Market-wide backbone series for the stock (already corporate-action adjusted).
        LocalDate since = LocalDate.now().minusDays(PRICE_LOOKBACK_DAYS);
        List<StockPriceHistory> stockSeries = backbonePriceReader.dailySeries(sym, since);

        CompanyProfile.QuoteContext quote = buildQuoteContext(deepDive, sym, sector, stockSeries, since, dataGaps);
        CompanyProfile.CorporateActionsCard corp = buildCorporateActionsCard(sym, deepDive, dataGaps);
        CompanyProfile.OwnershipCard ownership = buildOwnershipCard(sym, dataGaps);
        CompanyProfile.FundamentalsCard fundamentals = buildFundamentalsCard(deepDive.fundamentals());
        CompanyProfile.RiskFitCard riskFit =
                new CompanyProfile.RiskFitCard(deepDive.portfolioFit(), deepDive.riskMetrics());
        CompanyProfile.NewsPolicyCard newsPolicy = buildNewsPolicyCard(deepDive, sector);

        CompanyProfile draft = new CompanyProfile(
                sym,
                LocalDate.now(),
                deepDive.knownSymbol(),
                deepDive.hasPriceHistory(),
                sector,
                quote,
                corp,
                ownership,
                fundamentals,
                riskFit,
                newsPolicy,
                null,
                dataGaps.stream().distinct().toList());

        java.util.Map<String, String> narrations = beginnerNarrationService.narrate(draft);
        return new CompanyProfile(
                draft.symbol(),
                draft.asOf(),
                draft.knownSymbol(),
                draft.hasPriceHistory(),
                draft.sector(),
                draft.quoteContext(),
                draft.corporateActions(),
                draft.ownership(),
                draft.fundamentals(),
                draft.riskFit(),
                draft.newsPolicy(),
                narrations,
                draft.dataGaps());
    }

    // ── Card 1: quote & index context ─────────────────────────────────────────

    private CompanyProfile.QuoteContext buildQuoteContext(StockDeepDive deepDive,
                                                          String sym,
                                                          String sector,
                                                          List<StockPriceHistory> stockSeries,
                                                          LocalDate since,
                                                          List<String> dataGaps) {
        Double week52High = null;
        Double week52Low = null;
        Double pricePercentile = null;
        Double volumeZ = null;

        if (stockSeries != null && !stockSeries.isEmpty()) {
            LocalDate oneYearAgo = LocalDate.now().minusDays(365);
            List<Double> closes1y = stockSeries.stream()
                    .filter(h -> h.getPriceDate() != null && !h.getPriceDate().isBefore(oneYearAgo))
                    .map(this::close)
                    .filter(c -> c != null && c > 0)
                    .toList();
            if (!closes1y.isEmpty()) {
                week52High = closes1y.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
                week52Low = closes1y.stream().mapToDouble(Double::doubleValue).min().orElse(Double.NaN);
                Double last = deepDive.lastClose() != null ? deepDive.lastClose() : closes1y.get(closes1y.size() - 1);
                if (week52High > week52Low && last != null) {
                    pricePercentile = (last - week52Low) / (week52High - week52Low);
                }
            }
            volumeZ = volumeZScore(stockSeries);
        } else {
            dataGaps.add("DATA_INCOMPLETE:QUOTE_CONTEXT — no backbone price series for " + sym);
        }

        List<CompanyProfile.RelativeStrength> relStrength = buildRelativeStrength(sym, sector, since);

        return new CompanyProfile.QuoteContext(
                deepDive.lastClose(),
                deepDive.dayChangePercent(),
                deepDive.hitCircuitToday(),
                deepDive.circuitType(),
                week52High,
                week52Low,
                round(pricePercentile, 4),
                round(volumeZ, 2),
                relStrength);
    }

    private List<CompanyProfile.RelativeStrength> buildRelativeStrength(String sym, String sector, LocalDate since) {
        List<StockPriceHistory> stock = backbonePriceReader.dailySeries(sym, since);
        List<StockPriceHistory> nifty = backbonePriceReader.dailySeries(StockPriceService.NIFTY_SYMBOL, since);
        String sectorIndex = sector != null ? factorProperties.indexForSector(sector).orElse(null) : null;
        List<StockPriceHistory> sectorSeries = sectorIndex != null
                ? backbonePriceReader.dailySeries(sectorIndex, since) : List.of();

        if (stock == null || stock.isEmpty()) return List.of();

        int[] windows = {30, 90, 365};
        String[] labels = {"1M", "3M", "1Y"};
        List<CompanyProfile.RelativeStrength> out = new ArrayList<>();
        for (int i = 0; i < windows.length; i++) {
            LocalDate start = LocalDate.now().minusDays(windows[i]);
            Double sr = windowReturnPct(stock, start);
            Double nr = windowReturnPct(nifty, start);
            Double secr = windowReturnPct(sectorSeries, start);
            if (sr == null) continue;
            out.add(new CompanyProfile.RelativeStrength(
                    labels[i],
                    round(sr, 2),
                    round(nr, 2),
                    nr != null ? round(sr - nr, 2) : null,
                    sectorIndex,
                    round(secr, 2),
                    secr != null ? round(sr - secr, 2) : null));
        }
        return out;
    }

    /** Simple price return (%) from the first close on/after {@code start} to the latest close. */
    private Double windowReturnPct(List<StockPriceHistory> series, LocalDate start) {
        if (series == null || series.size() < 2) return null;
        Double startClose = null;
        for (StockPriceHistory h : series) {
            if (h.getPriceDate() != null && !h.getPriceDate().isBefore(start)) {
                Double c = close(h);
                if (c != null && c > 0) { startClose = c; break; }
            }
        }
        Double endClose = null;
        for (int i = series.size() - 1; i >= 0; i--) {
            Double c = close(series.get(i));
            if (c != null && c > 0) { endClose = c; break; }
        }
        if (startClose == null || endClose == null || startClose == 0) return null;
        return (endClose / startClose - 1.0) * 100.0;
    }

    private Double volumeZScore(List<StockPriceHistory> series) {
        List<Long> vols = series.stream()
                .map(StockPriceHistory::getVolume)
                .filter(v -> v != null && v > 0)
                .toList();
        if (vols.size() < 21) return null;
        List<Long> trailing = vols.subList(Math.max(0, vols.size() - 21), vols.size() - 1); // last 20 before latest
        long latest = vols.get(vols.size() - 1);
        double mean = trailing.stream().mapToLong(Long::longValue).average().orElse(0);
        double var = trailing.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        double sd = Math.sqrt(var);
        if (sd <= 0) return null;
        return (latest - mean) / sd;
    }

    private Double close(StockPriceHistory h) {
        BigDecimal c = h.getAdjustedClose() != null ? h.getAdjustedClose() : h.getClosePrice();
        return c != null ? c.doubleValue() : null;
    }

    // ── Card 2: corporate actions & events ────────────────────────────────────

    private CompanyProfile.CorporateActionsCard buildCorporateActionsCard(String sym,
                                                                          StockDeepDive deepDive,
                                                                          List<String> dataGaps) {
        Double lastClose = deepDive.lastClose();

        List<CompanyProfile.PastAction> past = corporateActionRepository
                .findBySymbolOrderByExDateDesc(sym).stream()
                .limit(MAX_PAST_ACTIONS)
                .map(a -> toPastAction(a, lastClose))
                .toList();
        if (past.isEmpty()) dataGaps.add("DATA_INCOMPLETE:CORPORATE_ACTIONS — none recorded for " + sym);

        LocalDate today = LocalDate.now();
        List<CompanyProfile.ForwardEvent> forward = corporateEventRepository
                .findBySymbolAndEventDateGreaterThanEqualOrderByEventDate(sym, today).stream()
                .limit(MAX_FORWARD_EVENTS)
                .map(e -> new CompanyProfile.ForwardEvent(
                        e.getEventType() != null ? e.getEventType().name() : null,
                        e.getEventDate(),
                        e.getDescription(),
                        ChronoUnit.DAYS.between(today, e.getEventDate())))
                .toList();

        EventStudyService.EventStudyResult drift = null;
        List<LocalDate> resultDates = corporateEventRepository
                .findBySymbolAndEventTypeAndEventDateLessThanOrderByEventDateDesc(sym, CorporateEventType.RESULTS, today)
                .stream()
                .limit(MAX_RESULTS_EVENTS)
                .map(CorporateEvent::getEventDate)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!resultDates.isEmpty()) {
            drift = eventStudyService.studyPostEvent(sym, resultDates, EVENT_STUDY_WINDOW).orElse(null);
        }

        return new CompanyProfile.CorporateActionsCard(past, forward, drift);
    }

    private CompanyProfile.PastAction toPastAction(CorporateAction a, Double lastClose) {
        Double yieldPct = null;
        if (a.getActionType() == org.amit.finwise.marketdata.model.CorporateActionType.DIVIDEND
                && a.getDividendAmount() != null && lastClose != null && lastClose > 0) {
            yieldPct = round(a.getDividendAmount().doubleValue() / lastClose * 100.0, 2);
        }
        return new CompanyProfile.PastAction(
                a.getActionType() != null ? a.getActionType().name() : null,
                a.getSubject(),
                a.getExDate(),
                a.getRecordDate(),
                a.getDividendAmount(),
                a.getRatioNew(),
                a.getRatioOld(),
                yieldPct);
    }

    // ── Card 3: ownership & smart money ───────────────────────────────────────

    private CompanyProfile.OwnershipCard buildOwnershipCard(String sym, List<String> dataGaps) {
        List<ShareholdingPattern> patterns = shareholdingPatternRepository
                .findBySymbolOrderByPeriodEndedDesc(sym).stream()
                .limit(4)
                .toList();

        List<CompanyProfile.ShareholdingQuarter> trend = new ArrayList<>();
        for (int i = 0; i < patterns.size(); i++) {
            ShareholdingPattern cur = patterns.get(i);
            ShareholdingPattern prev = (i + 1 < patterns.size()) ? patterns.get(i + 1) : null;
            trend.add(new CompanyProfile.ShareholdingQuarter(
                    cur.getPeriodEnded(),
                    cur.getPromoterPct(),
                    cur.getPromoterPledgePct(),
                    cur.getFiiPct(),
                    cur.getDiiPct(),
                    cur.getPublicPct(),
                    delta(cur.getPromoterPct(), prev != null ? prev.getPromoterPct() : null),
                    delta(cur.getFiiPct(), prev != null ? prev.getFiiPct() : null),
                    delta(cur.getDiiPct(), prev != null ? prev.getDiiPct() : null)));
        }

        BigDecimal latestPledge = patterns.isEmpty() ? null : patterns.get(0).getPromoterPledgePct();

        Integer momentum = null;
        String momentumLabel = null;
        if (patterns.size() >= 2) {
            BigDecimal dFii = delta(patterns.get(0).getFiiPct(), patterns.get(1).getFiiPct());
            BigDecimal dDii = delta(patterns.get(0).getDiiPct(), patterns.get(1).getDiiPct());
            double combined = (dFii != null ? dFii.doubleValue() : 0) + (dDii != null ? dDii.doubleValue() : 0);
            momentum = (int) Math.signum(combined);
            momentumLabel = switch (momentum) {
                case 1 -> "Institutions accumulating (FII+DII rising QoQ)";
                case -1 -> "Institutions reducing (FII+DII falling QoQ)";
                default -> "Institutional ownership flat QoQ";
            };
        } else {
            dataGaps.add("DATA_INCOMPLETE:OWNERSHIP — <2 quarters of shareholding data for " + sym);
        }

        List<CompanyProfile.RecentDeal> deals = marketDealRepository
                .findBySymbolOrderByDealDateDesc(sym).stream()
                .limit(MAX_DEALS)
                .map(d -> new CompanyProfile.RecentDeal(
                        d.getDealDate(),
                        d.getDealType() != null ? d.getDealType().name() : null,
                        d.getClientName(),
                        d.getSide() != null ? d.getSide().name() : null,
                        d.getQuantity(),
                        d.getPrice()))
                .toList();

        return new CompanyProfile.OwnershipCard(trend, latestPledge, momentum, momentumLabel, deals);
    }

    private BigDecimal delta(BigDecimal cur, BigDecimal prev) {
        if (cur == null || prev == null) return null;
        return cur.subtract(prev).setScale(4, RoundingMode.HALF_UP);
    }

    // ── Card 4: fundamentals & valuation ──────────────────────────────────────

    private CompanyProfile.FundamentalsCard buildFundamentalsCard(StockFundamentals fundamentals) {
        if (fundamentals == null) {
            return new CompanyProfile.FundamentalsCard(null, List.of());
        }
        List<String> verdicts = new ArrayList<>();
        if (fundamentals.getValuationLabel() != null && fundamentals.getPeZScore() != null) {
            verdicts.add(String.format(Locale.ENGLISH,
                    "Valuation %s (P/E z-score %.2f vs its own 2-year history)",
                    fundamentals.getValuationLabel(), fundamentals.getPeZScore().doubleValue()));
        }
        if (fundamentals.getTrailingPE() != null && fundamentals.getPeerPePercentile() != null) {
            double pct = fundamentals.getPeerPePercentile().doubleValue();
            verdicts.add(String.format(Locale.ENGLISH,
                    "P/E %.1f — pricier than %.0f%% of peers",
                    fundamentals.getTrailingPE().doubleValue(), pct));
        }
        if (fundamentals.getPriceToBook() != null && fundamentals.getPeerPbPercentile() != null) {
            double pct = fundamentals.getPeerPbPercentile().doubleValue();
            verdicts.add(String.format(Locale.ENGLISH,
                    "P/B %.1f — richer than %.0f%% of peers",
                    fundamentals.getPriceToBook().doubleValue(), pct));
        }
        if (fundamentals.getPiotroskiFScore() != null && fundamentals.getPiotroskiMaxChecks() != null) {
            verdicts.add(String.format(Locale.ENGLISH,
                    "Quality (modified Piotroski): %d/%d checks passed",
                    fundamentals.getPiotroskiFScore(), fundamentals.getPiotroskiMaxChecks()));
        }
        return new CompanyProfile.FundamentalsCard(fundamentals, verdicts);
    }

    // ── Card 6: news & policy catalysts ───────────────────────────────────────

    private CompanyProfile.NewsPolicyCard buildNewsPolicyCard(StockDeepDive deepDive, String sector) {
        List<PolicyEventCard> policyExposure = List.of();
        try {
            Set<String> factors = factorsForSector(sector);
            Set<String> assetClasses = Set.of("Equity");
            policyExposure = policyIntelligenceService.getStockPolicyExposure(
                    sector, factors, assetClasses, POLICY_LIMIT);
        } catch (Exception e) {
            log.warn("[CompanyProfile] policy exposure lookup failed: {}", e.getMessage());
        }
        return new CompanyProfile.NewsPolicyCard(deepDive.relevantNews(), policyExposure);
    }

    /** Map a sector to the policy FACTOR subjects used by the rule/LLM impact tagger. */
    private Set<String> factorsForSector(String sector) {
        if (sector == null) return Set.of();
        String s = sector.toLowerCase(Locale.ENGLISH);
        Set<String> factors = new LinkedHashSet<>();
        if (s.contains("bank") || s.contains("financ") || s.contains("nbfc")
                || s.contains("realty") || s.contains("housing")) {
            factors.add("Rate Sensitive");
        }
        if (s.contains("capital market") || s.contains("broker") || s.contains("amc")) {
            factors.add("Market Liquidity");
        }
        return factors;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String resolveSector(String sym, StockDeepDive deepDive) {
        if (deepDive.portfolioFit() != null && deepDive.portfolioFit().sector() != null) {
            return deepDive.portfolioFit().sector();
        }
        SymbolExtractorService.SymbolEntry entry = symbolExtractorService.getSymbolEntry(sym);
        return entry != null ? entry.sector : null;
    }

    private Double round(Double value, int scale) {
        if (value == null || value.isNaN() || value.isInfinite()) return null;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
