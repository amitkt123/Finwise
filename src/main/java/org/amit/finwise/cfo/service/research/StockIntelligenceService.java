package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.service.analytics.CovarianceEngine;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.cfo.service.analytics.TechnicalAnalysisService;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.cfo.model.MacroSnapshot;
import org.amit.finwise.cfo.service.macro.MacroStateService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Assembles the full research evidence pack for a named stock.
 *
 * Phase 3 scaffold — wires together what already exists:
 *   - Live quote + OHLCV from StockPriceHistory
 *   - Technical indicators from TechnicalAnalysisService (Phase 2)
 *   - Beta and portfolio fit from PortfolioRiskService (Phase 1)
 *   - Stock-specific news from NewsArticleRepository
 *
 * Phases 4 (fundamentals/valuation) and 5 (macro/catalysts) will add to
 * StockDeepDive without changing the routing or rendering logic.
 *
 * Quality gate: if the symbol has no price history, DataGaps will contain
 * DATA_INCOMPLETE:PRICE_HISTORY and the returned DeepDive carries hasPriceHistory=false.
 * The research system prompt then explicitly forbids the LLM from substituting
 * training-data recall for the missing live data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockIntelligenceService {

    private final StockPriceHistoryRepository priceRepo;
    private final NewsArticleRepository newsRepo;
    private final InvestmentRepository investmentRepo;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final PortfolioRiskService portfolioRiskService;
    private final ReturnSeriesService returnSeriesService;
    private final CovarianceEngine covarianceEngine;
    private final SymbolExtractorService symbolExtractorService;
    private final FundamentalsService fundamentalsService;
    private final MacroStateService macroStateService;

    private static final int NEWS_LOOKBACK_DAYS = 7;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the full research evidence pack for {@code symbol} in the context of
     * {@code userId}'s portfolio.
     *
     * Always returns a non-null DeepDive; missing data is captured in dataGaps
     * rather than surfacing as exceptions.
     */
    public StockDeepDive analyze(String symbol, String userId) {
        String sym = symbol.toUpperCase().trim();
        List<String> dataGaps = new ArrayList<>();

        // ── Symbol known? ──────────────────────────────────────────────────────
        boolean knownSymbol = symbolExtractorService.mentionsSymbol(sym, sym)
                || priceRepo.findTopBySymbolOrderByPriceDateDesc(sym).isPresent();

        // ── Live quote ─────────────────────────────────────────────────────────
        Optional<StockPriceHistory> latestOpt = priceRepo.findTopBySymbolOrderByPriceDateDesc(sym);
        Double lastClose = null;
        Double dayChangePct = null;
        boolean hitCircuit = false;
        String circuitType = null;
        boolean hasPriceHistory = latestOpt.isPresent();

        if (latestOpt.isPresent()) {
            StockPriceHistory latest = latestOpt.get();
            lastClose    = latest.getClosePrice() != null ? latest.getClosePrice().doubleValue() : null;
            dayChangePct = latest.getPriceChangePercent();
            if (Boolean.TRUE.equals(latest.getHitUpperCircuit())) { hitCircuit = true; circuitType = "UPPER"; }
            else if (Boolean.TRUE.equals(latest.getHitLowerCircuit())) { hitCircuit = true; circuitType = "LOWER"; }
        } else {
            dataGaps.add("DATA_INCOMPLETE:PRICE_HISTORY — no price data found for " + sym);
            log.info("[DeepDive] {} — no price history; returning thin DeepDive", sym);
        }

        // ── Technical analysis (Phase 2) ─────────────────────────────────────
        Optional<TechnicalSnapshot> techOpt = technicalAnalysisService.analyze(sym);
        TechnicalSnapshot technicals = techOpt.orElse(null);
        if (technicals == null && hasPriceHistory) {
            dataGaps.add("DATA_INCOMPLETE:TECHNICALS — insufficient price history (<15 days)");
        }

        // ── Fundamentals & Valuation (Phase 4) ───────────────────────────────
        org.amit.finwise.cfo.model.StockFundamentals fundamentals = null;
        try {
            fundamentals = fundamentalsService.getLatest(sym);
            if (fundamentals != null && fundamentals.getDataQualityNotes() != null) {
                dataGaps.add("DATA_INCOMPLETE:FUNDAMENTALS — " + fundamentals.getDataQualityNotes());
            }
        } catch (Exception e) {
            log.warn("[DeepDive] Fundamentals fetch failed for {}: {}", sym, e.getMessage());
            dataGaps.add("DATA_INCOMPLETE:FUNDAMENTALS — fetch error");
        }

        // ── Stock-specific news ───────────────────────────────────────────────
        LocalDate newsSince = LocalDate.now().minusDays(NEWS_LOOKBACK_DAYS);
        List<NewsArticle> allRecent = newsRepo.findRecentByRelevance(newsSince);
        List<NewsArticle> stockNews = allRecent.stream()
                .filter(a -> symbolExtractorService.mentionsSymbol(
                        (a.getTitle() != null ? a.getTitle() : "") + " " +
                        (a.getSummary() != null ? a.getSummary() : ""), sym))
                .limit(6)
                .toList();

        // ── Macro context (Phase 5) ──────────────────────────────────────────
        MacroSnapshot macroSnapshot = null;
        try {
            Optional<MacroSnapshot> macroOpt = macroStateService.getLatest();
            if (macroOpt.isPresent()) {
                macroSnapshot = macroOpt.get();
            }
        } catch (Exception e) {
            log.warn("[DeepDive] Macro snapshot fetch failed: {}", e.getMessage());
            dataGaps.add("DATA_INCOMPLETE:MACRO — fetch error");
        }

        // ── Portfolio fit ─────────────────────────────────────────────────────
        StockDeepDive.PortfolioFit fit = buildPortfolioFit(sym, userId, dataGaps, technicals, fundamentals);

        return new StockDeepDive(
                sym,
                LocalDate.now(),
                lastClose,
                dayChangePct,
                hitCircuit,
                circuitType,
                technicals,
                fundamentals,
                stockNews,
                macroSnapshot,
                fit,
                knownSymbol,
                hasPriceHistory,
                dataGaps
        );
    }

    // ─────────────────────────────────────────────────────────────────────────

    private StockDeepDive.PortfolioFit buildPortfolioFit(String sym, String userId,
                                                          List<String> dataGaps,
                                                          TechnicalSnapshot technicals,
                                                          org.amit.finwise.cfo.model.StockFundamentals fundamentals) {
        // Check if stock is already held
        List<Investment> investments = investmentRepo.findActiveInvestments(userId);
        Optional<Investment> heldOpt = investments.stream()
                .filter(inv -> sym.equals(inv.getSymbol() != null ? inv.getSymbol().toUpperCase() : ""))
                .findFirst();

        boolean isHeld = heldOpt.isPresent();
        String sector = null;

        // Sector: from held investment, or from gazetteer
        if (isHeld) {
            sector = heldOpt.get().getSector();
        } else {
            SymbolExtractorService.SymbolEntry entry =
                    symbolExtractorService.getSymbolEntry(sym);
            if (entry != null) sector = entry.sector;
        }

        // Weight: if held, compute from portfolio
        double weight = 0.0;
        if (isHeld && heldOpt.get().getCurrentValue() != null) {
            double totalValue = investments.stream()
                    .filter(inv -> inv.getCurrentValue() != null)
                    .mapToDouble(inv -> inv.getCurrentValue().doubleValue())
                    .sum();
            if (totalValue > 0) {
                weight = heldOpt.get().getCurrentValue().doubleValue() / totalValue;
            }
        }

        // Beta and %CTR from Phase 1 (if held and risk engine has data)
        double betaVsNifty = Double.NaN;
        double pctContrib  = Double.NaN;

        Optional<RiskDecomposition> rdOpt = portfolioRiskService.compute(userId);
        if (rdOpt.isPresent()) {
            RiskDecomposition rd = rdOpt.get();
            if (isHeld) {
                // Find the holding in the risk contributors list
                rd.riskContributors().stream()
                        .filter(c -> sym.equals(c.symbol()))
                        .findFirst()
                        .ifPresent(c -> {
                            // We can't reassign betaVsNifty/pctContrib in a lambda; use arrays
                        });
                // Direct field lookup from perHoldingBeta map
                betaVsNifty = rd.perHoldingBeta().getOrDefault(sym, Double.NaN);
                pctContrib  = rd.riskContributors().stream()
                        .filter(c -> sym.equals(c.symbol()))
                        .mapToDouble(c -> c.percentContributionToRisk() * 100)
                        .findFirst().orElse(Double.NaN);
            } else {
                // Not held — compute beta of this stock vs Nifty directly
                betaVsNifty = computeStandaloneBeta(sym, dataGaps);
            }
        } else if (!isHeld) {
            betaVsNifty = computeStandaloneBeta(sym, dataGaps);
        }

        // Build fit summary
        String fitSummary = buildFitSummary(sym, isHeld, weight, betaVsNifty, pctContrib, sector);

        // Phase 7: Marginal vol impact, correlation, verdict
        CovarianceEngine.MarginalImpact marginalImpact = CovarianceEngine.MarginalImpact.unavailable();
        String verdictLabel = "NEEDS-MORE-DATA";
        String verdictRationale = "Insufficient data for verdict";

        try {
            // Compute marginal vol impact
            LocalDate since = LocalDate.now().minusDays(365);
            List<String> symbolsForMarginal = new ArrayList<>(investments.stream()
                    .filter(inv -> inv.getSymbol() != null)
                    .map(inv -> inv.getSymbol().toUpperCase())
                    .toList());
            if (!symbolsForMarginal.contains(sym.toUpperCase())) {
                symbolsForMarginal.add(sym.toUpperCase());
            }
            Map<String, NavigableMap<LocalDate, Double>> returnSeries =
                    returnSeriesService.getReturnSeries(symbolsForMarginal, since);

            // Build existing weights (only current holdings, not the candidate)
            Map<String, Double> existingWeights = new java.util.HashMap<>();
            double totalValue = investments.stream()
                    .filter(inv -> inv.getCurrentValue() != null && !sym.equals(inv.getSymbol() != null ? inv.getSymbol().toUpperCase() : ""))
                    .mapToDouble(inv -> inv.getCurrentValue().doubleValue())
                    .sum();
            if (totalValue > 0) {
                for (Investment inv : investments) {
                    if (inv.getSymbol() != null && inv.getCurrentValue() != null &&
                        !sym.equals(inv.getSymbol().toUpperCase())) {
                        existingWeights.put(inv.getSymbol().toUpperCase(),
                                inv.getCurrentValue().doubleValue() / totalValue);
                    }
                }
            }

            // Compute marginal impact
            if (!existingWeights.isEmpty()) {
                marginalImpact = covarianceEngine.marginalVolImpact(returnSeries, existingWeights, sym, 0.05);
            } else {
                dataGaps.add("DATA_INCOMPLETE:MARGINAL_VOL — portfolio has no other equity holdings");
            }

            if (!Double.isNaN(marginalImpact.deltaVol())) {
                // Build verdict based on computed numbers and optionals
                verdictLabel = computeVerdictLabel(sym, marginalImpact, technicals, fundamentals);
                verdictRationale = computeVerdictRationale(verdictLabel, marginalImpact, technicals, fundamentals);
            }
        } catch (Exception e) {
            log.warn("[DeepDive] Marginal vol computation failed for {}: {}", sym, e.getMessage());
            dataGaps.add("DATA_INCOMPLETE:MARGINAL_VOL — computation error");
        }

        return new StockDeepDive.PortfolioFit(
                isHeld, weight, betaVsNifty, pctContrib, sector, fitSummary,
                marginalImpact.correlationToPortfolio(), marginalImpact.deltaVol(),
                marginalImpact.newVolAnnualized(), marginalImpact.hypotheticalWeight(),
                verdictLabel, verdictRationale
        );
    }

    private String computeVerdictLabel(String sym, CovarianceEngine.MarginalImpact marginalImpact,
                                      TechnicalSnapshot technicals, org.amit.finwise.cfo.model.StockFundamentals fundamentals) {
        double rho = marginalImpact.correlationToPortfolio();
        double deltaVol = marginalImpact.deltaVol();

        // Rule 1: Strong diversification signal
        if (!Double.isNaN(rho) && !Double.isNaN(deltaVol) && rho < 0.30 && deltaVol < 0) {
            return "DIVERSIFY";
        }

        // Rule 2: Concentration risk signal
        if (!Double.isNaN(rho) && !Double.isNaN(deltaVol) && (rho > 0.70 || deltaVol > 0.03)) {
            return "CONCENTRATE-RISK";
        }

        // Rule 3: Overbought + Expensive
        if (technicals != null && technicals.rsi14() >= 70 &&
            fundamentals != null && fundamentals.getPeZScore() != null && fundamentals.getPeZScore().doubleValue() > 1.5) {
            return "AVOID";
        }

        // Rule 4: Wait for pullback (cheap/fair valuation + elevated RSI)
        if (fundamentals != null && "CHEAP".equals(fundamentals.getValuationLabel()) &&
            technicals != null && technicals.rsi14() >= 65) {
            return "WAIT-FOR-PULLBACK";
        }
        if (fundamentals != null && "FAIR".equals(fundamentals.getValuationLabel()) &&
            technicals != null && technicals.rsi14() >= 65) {
            return "WAIT-FOR-PULLBACK";
        }

        // Rule 5: Initiate
        if (fundamentals != null && "CHEAP".equals(fundamentals.getValuationLabel()) &&
            technicals != null && technicals.rsi14() < 65 &&
            !Double.isNaN(deltaVol) && deltaVol <= 0.02) {
            return "INITIATE";
        }

        return "NEEDS-MORE-DATA";
    }

    private String computeVerdictRationale(String verdict, CovarianceEngine.MarginalImpact marginalImpact,
                                          TechnicalSnapshot technicals, org.amit.finwise.cfo.model.StockFundamentals fundamentals) {
        StringBuilder sb = new StringBuilder();

        switch (verdict) {
            case "DIVERSIFY":
                sb.append(String.format("ρ=%.2f (low overlap) reduces portfolio vol by %.2f%%",
                        marginalImpact.correlationToPortfolio(), -marginalImpact.deltaVol() * 100));
                break;
            case "CONCENTRATE-RISK":
                double rho = marginalImpact.correlationToPortfolio();
                double deltaVol = marginalImpact.deltaVol();
                if (!Double.isNaN(rho) && rho > 0.70) {
                    sb.append(String.format("ρ=%.2f (high portfolio overlap) + ", rho));
                } else if (!Double.isNaN(deltaVol)) {
                    sb.append(String.format("Δσ=+%.2f%% (high vol impact) ", deltaVol * 100));
                }
                break;
            case "AVOID":
                if (technicals != null) {
                    sb.append(String.format("RSI=%.0f (OVERBOUGHT) + ",
                            technicals.rsi14()));
                }
                if (fundamentals != null && fundamentals.getPeZScore() != null) {
                    sb.append(String.format("P/E z-score=%.2f (EXPENSIVE)",
                            fundamentals.getPeZScore().doubleValue()));
                }
                break;
            case "WAIT-FOR-PULLBACK":
                if (technicals != null) {
                    sb.append(String.format("%s valuation + RSI=%.0f (elevated, await pullback)",
                            fundamentals != null ? fundamentals.getValuationLabel() : "fair",
                            technicals.rsi14()));
                }
                break;
            case "INITIATE":
                if (fundamentals != null && technicals != null) {
                    sb.append(String.format("%s valuation + RSI=%.0f (neutral momentum) + ",
                            fundamentals.getValuationLabel() != null ? fundamentals.getValuationLabel() : "CHEAP",
                            technicals.rsi14()));
                }
                if (!Double.isNaN(marginalImpact.deltaVol())) {
                    sb.append(String.format("Δσ=+%.2f%% (manageable risk impact)",
                            marginalImpact.deltaVol() * 100));
                }
                break;
            default:
                sb.append("Insufficient data for a high-confidence verdict");
        }

        return sb.toString();
    }

    private double computeStandaloneBeta(String sym, List<String> dataGaps) {
        try {
            LocalDate since = LocalDate.now().minusDays(400);
            Map<String, NavigableMap<LocalDate, Double>> series =
                    returnSeriesService.getReturnSeries(
                            List.of(sym, org.amit.finwise.cfo.service.StockPriceService.NIFTY_SYMBOL), since);

            NavigableMap<LocalDate, Double> stockSeries = series.get(sym);
            NavigableMap<LocalDate, Double> niftySeries =
                    series.get(org.amit.finwise.cfo.service.StockPriceService.NIFTY_SYMBOL);

            if (stockSeries == null) {
                dataGaps.add("DATA_INCOMPLETE:BETA — insufficient return history for " + sym);
                return Double.NaN;
            }
            if (niftySeries == null) {
                dataGaps.add("DATA_INCOMPLETE:BETA — Nifty benchmark series unavailable");
                return Double.NaN;
            }

            CovarianceEngine.BetaStats bs = covarianceEngine.betaStats(stockSeries, niftySeries);
            return bs.beta();
        } catch (Exception e) {
            log.warn("[DeepDive] Beta computation failed for {}: {}", sym, e.getMessage());
            dataGaps.add("DATA_INCOMPLETE:BETA — computation error");
            return Double.NaN;
        }
    }

    private String buildFitSummary(String sym, boolean isHeld, double weight,
                                    double beta, double pctContrib, String sector) {
        StringBuilder sb = new StringBuilder();
        if (isHeld) {
            sb.append(String.format("%s is held at %.1f%% portfolio weight", sym, weight * 100));
            if (!Double.isNaN(pctContrib))
                sb.append(String.format(", contributing %.1f%% of portfolio volatility", pctContrib));
        } else {
            sb.append(sym).append(" is NOT currently held");
        }
        if (!Double.isNaN(beta))
            sb.append(String.format(" | β vs Nifty: %.2f", beta));
        if (sector != null)
            sb.append(" | Sector: ").append(sector);
        return sb.toString();
    }
}
