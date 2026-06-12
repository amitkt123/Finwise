package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.EvidenceItem;
import org.amit.finwise.cfo.model.MacroSnapshot;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.model.StockScorecard;
import org.amit.finwise.cfo.model.StockScorecardSnapshot;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.amit.finwise.cfo.repository.StockScorecardSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class StockScorecardService {

    /** Total-score margin past a decision boundary required to flip a recommendation. */
    private static final double HYSTERESIS_MARGIN = 3.0;
    /** WAIT/AVOID boundary on the total score. */
    private static final double AVOID_BOUNDARY = 45.0;

    private final StockScorecardSnapshotRepository snapshotRepo;
    private final double logisticCenter;
    private final double logisticScale;

    public StockScorecardService(@Autowired(required = false) StockScorecardSnapshotRepository snapshotRepo,
                                 @Value("${cfo.scorecard.logistic-center:62}") double logisticCenter,
                                 @Value("${cfo.scorecard.logistic-scale:6}") double logisticScale) {
        this.snapshotRepo = snapshotRepo;
        this.logisticCenter = logisticCenter;
        this.logisticScale = logisticScale;
    }

    public StockScorecard build(String symbol,
                                LocalDate asOf,
                                TechnicalSnapshot technicals,
                                StockFundamentals fundamentals,
                                FundamentalTrendService.TrendAnalysis quarterlyTrend,
                                List<NewsArticle> news,
                                MacroSnapshot macro,
                                StockDeepDive.PortfolioFit portfolioFit,
                                StockDeepDive.RiskMetrics riskMetrics,
                                List<String> upstreamDataGaps,
                                int priceObservationCount,
                                boolean hasAdjustedPrices) {
        List<String> dataGaps = new ArrayList<>(upstreamDataGaps);
        List<EvidenceItem> evidence = new ArrayList<>();
        List<String> bullCase = new ArrayList<>();
        List<String> bearCase = new ArrayList<>();
        List<String> keyRisks = new ArrayList<>();

        String sector = portfolioFit != null ? portfolioFit.sector() : null;
        int valuation = valuationScore(fundamentals, sector, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int quality = qualityScore(fundamentals, sector, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int growth = growthScore(fundamentals, quarterlyTrend, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int momentum = momentumScore(technicals, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int risk = riskScore(technicals, portfolioFit, riskMetrics, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int newsMacro = newsMacroScore(symbol, sector, news, macro, evidence, asOf, bullCase, bearCase, keyRisks, dataGaps);
        int fit = portfolioFitScore(portfolioFit, evidence, asOf, bullCase, bearCase, dataGaps);

        double total = round1(
                0.20 * valuation +
                0.20 * quality +
                0.15 * growth +
                0.15 * momentum +
                0.15 * risk +
                0.10 * newsMacro +
                0.05 * fit);

        int confidence = confidence(fundamentals, macro, news, priceObservationCount, hasAdjustedPrices, dataGaps);
        StockScorecard.Recommendation recommendation = recommendation(total, confidence, valuation, risk, newsMacro);
        recommendation = applyHysteresis(symbol, recommendation, total, valuation, risk, newsMacro, evidence, asOf);
        String expectedReturnBand = expectedReturnBand(valuation, quality, growth, fundamentals);

        if (bullCase.isEmpty()) bullCase.add("No strong positive evidence available in current data.");
        if (bearCase.isEmpty()) bearCase.add("No strong negative evidence available in current data.");
        if (keyRisks.isEmpty()) keyRisks.add("Scorecard relies on available public/free data and may miss filings or paid feed details.");

        return new StockScorecard(symbol, asOf, total, recommendation, confidence,
                valuation, quality, growth, momentum, risk, newsMacro, fit,
                expectedReturnBand, List.copyOf(bullCase), List.copyOf(bearCase),
                List.copyOf(keyRisks), List.copyOf(dataGaps), List.copyOf(evidence));
    }

    public StockScorecard.Recommendation recommendation(double totalScore, int confidence,
                                                        int valuationScore, int riskScore, int newsMacroScore) {
        if (confidence < 50) return StockScorecard.Recommendation.NEEDS_MORE_DATA;

        // Hard AVOID guards stay (always respected, never subject to hysteresis)
        if (hardAvoid(valuationScore, riskScore, newsMacroScore)) {
            return StockScorecard.Recommendation.AVOID;
        }

        // BUY requires both high conviction AND confidence
        if (convictionPct(totalScore) >= 70 && confidence >= 70) {
            return StockScorecard.Recommendation.BUY;
        }

        if (totalScore < AVOID_BOUNDARY) {
            return StockScorecard.Recommendation.AVOID;
        }

        return StockScorecard.Recommendation.WAIT;
    }

    /** Probabilistic conviction: logistic over the total score, configurable center/scale. */
    public double convictionPct(double totalScore) {
        return 100.0 / (1.0 + Math.exp(-(totalScore - logisticCenter) / logisticScale));
    }

    private static boolean hardAvoid(int valuationScore, int riskScore, int newsMacroScore) {
        return riskScore < 25 || valuationScore < 20 || newsMacroScore < 25;
    }

    /** Total score at which conviction crosses the BUY threshold (70%). */
    private double buyBoundaryTotal() {
        return logisticCenter + logisticScale * Math.log(70.0 / 30.0);
    }

    /**
     * Hysteresis guard: a recommendation only flips relative to the latest
     * persisted scorecard snapshot if the total score has moved at least
     * {@link #HYSTERESIS_MARGIN} points past the decision boundary being
     * crossed. Prevents day-to-day flapping when the score oscillates around
     * a boundary. Safety paths bypass it: hard AVOID guards and
     * NEEDS_MORE_DATA always take effect immediately.
     */
    StockScorecard.Recommendation applyHysteresis(String symbol,
                                                  StockScorecard.Recommendation raw,
                                                  double total,
                                                  int valuationScore, int riskScore, int newsMacroScore,
                                                  List<EvidenceItem> evidence, LocalDate asOf) {
        if (snapshotRepo == null) return raw;
        if (raw == StockScorecard.Recommendation.NEEDS_MORE_DATA) return raw;
        if (hardAvoid(valuationScore, riskScore, newsMacroScore)) return raw;

        Optional<StockScorecardSnapshot> prevOpt =
                snapshotRepo.findTopBySymbolOrderByScorecardDateDescCreatedAtDesc(symbol);
        if (prevOpt.isEmpty()) return raw;

        StockScorecard.Recommendation prev = prevOpt.get().getRecommendation();
        if (prev == raw || prev == StockScorecard.Recommendation.NEEDS_MORE_DATA) return raw;

        // Which boundary would this flip cross, and is the score decisively past it?
        boolean decisive;
        if (raw == StockScorecard.Recommendation.BUY) {
            decisive = total >= buyBoundaryTotal() + HYSTERESIS_MARGIN;
        } else if (prev == StockScorecard.Recommendation.BUY) {
            // Falling out of BUY: WAIT crosses the BUY boundary, AVOID crosses 45.
            double boundary = raw == StockScorecard.Recommendation.AVOID ? AVOID_BOUNDARY : buyBoundaryTotal();
            decisive = total <= boundary - HYSTERESIS_MARGIN;
        } else if (raw == StockScorecard.Recommendation.AVOID) {
            decisive = total <= AVOID_BOUNDARY - HYSTERESIS_MARGIN;
        } else {
            // AVOID → WAIT
            decisive = total >= AVOID_BOUNDARY + HYSTERESIS_MARGIN;
        }

        if (decisive) return raw;

        add(evidence, "RECOMMENDATION", "Hysteresis hold", prev.name(),
                String.format("Score %.1f is within %.0f pts of the %s/%s boundary — holding previous call to avoid flapping",
                        total, HYSTERESIS_MARGIN, prev, raw),
                "BALANCED", 90, "scorecard_history", asOf);
        return prev;
    }

    private int valuationScore(StockFundamentals f, String sector, List<EvidenceItem> ev, LocalDate asOf,
                               List<String> bull, List<String> bear, List<String> risks, List<String> gaps) {
        if (f == null) {
            gaps.add("DATA_INCOMPLETE:FUNDAMENTALS — valuation unavailable");
            return 50;
        }
        double score = 55;
        boolean financial = isFinancialSector(sector);

        if (financial) {
            if (positive(f.getPriceToBook())) {
                double pb = f.getPriceToBook().doubleValue();
                score += pb < 1.5 ? 18 : pb <= 3 ? 5 : -15;
                add(ev, "VALUATION", "P/B", fmt(pb), financial ? "Bank/NBFC valuation anchor" : "Valuation multiple", "LOWER_BETTER", 75, "fundamentals", asOf);
            } else gaps.add("DATA_INCOMPLETE:VALUATION — P/B missing for financial sector stock");
            if (positive(f.getRoe())) score += f.getRoe().doubleValue() >= 15 ? 8 : -5;
        } else {
            if (positive(f.getTrailingPE())) {
                double pe = f.getTrailingPE().doubleValue();
                double[] bands = sectorPEBands(sector);
                score += pe < bands[0] ? 18 : pe <= bands[1] ? 4 : -18;
                add(ev, "VALUATION", "Trailing P/E", fmt(pe),
                        String.format("Earnings multiple vs Indian sector norms (cheap < %.0fx, expensive > %.0fx)", bands[0], bands[1]),
                        "LOWER_BETTER", 70, "fundamentals", asOf);
                add(ev, "EXPECTED_RETURN", "Earnings yield", fmt(100.0 / pe) + "%", "1 / trailing P/E; qualitative input, not a price target", "HIGHER_BETTER", 65, "fundamentals", asOf);
            } else gaps.add("DATA_INCOMPLETE:VALUATION — trailing P/E missing");
            if (positive(f.getEvToEbitda())) {
                double evEbitda = f.getEvToEbitda().doubleValue();
                score += evEbitda < 12 ? 8 : evEbitda > 25 ? -8 : 0;
                add(ev, "VALUATION", "EV/EBITDA", fmt(evEbitda), "Enterprise-value multiple cross-check", "LOWER_BETTER", 65, "fundamentals", asOf);
            }
        }

        if (f.getPeZScore() != null) {
            double z = f.getPeZScore().doubleValue();
            score += z < -1 ? 10 : z > 1.5 ? -15 : 0;
            add(ev, "VALUATION", "Own-history P/E z-score", fmt(z), "Fallback when peer data is unavailable", "LOWER_BETTER", 55, "fundamentals", asOf);
        }

        boolean hasPeerValuation = false;
        if (f.getPeerPePercentile() != null) {
            hasPeerValuation = true;
            double percentile = f.getPeerPePercentile().doubleValue();
            score += percentile <= 35 ? 12 : percentile >= 80 ? -14 : 3;
            add(ev, "VALUATION", "Peer P/E percentile", fmt(percentile),
                    peerInterpretation(f.getPeerGroup(), "P/E"), "LOWER_BETTER", 80, "normalized_peer_fundamentals", asOf);
        }
        if (financial && f.getPeerPbPercentile() != null) {
            hasPeerValuation = true;
            double percentile = f.getPeerPbPercentile().doubleValue();
            score += percentile <= 35 ? 10 : percentile >= 80 ? -12 : 2;
            add(ev, "VALUATION", "Peer P/B percentile", fmt(percentile),
                    peerInterpretation(f.getPeerGroup(), "P/B"), "LOWER_BETTER", 80, "normalized_peer_fundamentals", asOf);
        }
        if (!hasPeerValuation && f.getPeZScore() == null) {
            gaps.add("DATA_INCOMPLETE:PEER_VALUATION — peer percentile unavailable; own-history z-score missing");
        }

        if (isValueTrap(f)) {
            score -= 20;
            bear.add("Cheapness is penalized because growth, margins, leverage, or FCF look weak.");
            risks.add("Potential value trap: low multiple evidence is not enough without quality support.");
        }
        if (positive(f.getDividendYield())) {
            add(ev, "EXPECTED_RETURN", "Shareholder yield", fmt(f.getDividendYield().doubleValue()) + "%",
                    "Dividend yield used as v1 shareholder yield", "HIGHER_BETTER", 60, "fundamentals", asOf);
        }
        if (score >= 70) bull.add("Valuation is supportive after sector-aware checks.");
        if (score <= 40) bear.add("Valuation evidence is weak or expensive relative to available fundamentals.");
        return clampScore(score);
    }

    private int qualityScore(StockFundamentals f, String sector, List<EvidenceItem> ev, LocalDate asOf,
                             List<String> bull, List<String> bear, List<String> risks, List<String> gaps) {
        if (f == null) return 45;
        double score = 50;
        if (Boolean.TRUE.equals(f.getIsProfitable())) score += 12;
        if (positive(f.getRoe())) score += f.getRoe().doubleValue() >= (isFinancialSector(sector) ? 14 : 12) ? 14 : 2;
        else gaps.add("DATA_INCOMPLETE:QUALITY — ROE missing");
        if (positive(f.getOperatingMargin())) score += f.getOperatingMargin().doubleValue() >= 15 ? 10 : -3;
        else gaps.add("DATA_INCOMPLETE:QUALITY — operating margin missing");
        if (f.getDebtToEquity() != null) score += f.getDebtToEquity().doubleValue() <= 1.0 ? 8 : -12;
        if (f.getFreeCashFlow() != null) score += f.getFreeCashFlow().compareTo(BigDecimal.ZERO) > 0 ? 8 : -10;
        if (f.getPiotroskiFScore() != null && f.getPiotroskiMaxChecks() != null && f.getPiotroskiMaxChecks() >= 4) {
            double passRatio = (double) f.getPiotroskiFScore() / f.getPiotroskiMaxChecks();
            score += passRatio >= 0.8 ? 10 : passRatio <= 0.4 ? -10 : 0;
            add(ev, "QUALITY", "Piotroski F-Score (modified)",
                    f.getPiotroskiFScore() + "/" + f.getPiotroskiMaxChecks(),
                    "Checks passed of computable F-Score signals (" + nullToEmpty(f.getPiotroskiDetail()) + ")",
                    "HIGHER_BETTER", 70, "fundamentals", asOf);
        } else {
            gaps.add("DATA_INCOMPLETE:PIOTROSKI — needs ≥4 computable checks (YoY snapshot history)");
        }
        if (isFinancialSector(sector)) {
            score += bankAssetQualityScore(f, ev, asOf, gaps);
        }
        add(ev, "QUALITY", "Profitability", Boolean.TRUE.equals(f.getIsProfitable()) ? "profitable" : "not confirmed",
                "Profitability, ROE, margins, leverage and FCF composite", "HIGHER_BETTER", 70, "fundamentals", asOf);
        if (score >= 70) bull.add("Quality metrics support the valuation case.");
        if (score <= 40) bear.add("Quality metrics do not support a strong buy case.");
        return clampScore(score);
    }

    private int growthScore(StockFundamentals f, FundamentalTrendService.TrendAnalysis trend,
                            List<EvidenceItem> ev, LocalDate asOf,
                            List<String> bull, List<String> bear, List<String> risks, List<String> gaps) {
        if (f == null && trend == null) return 45;
        double score = 50;
        if (f != null) {
            if (f.getRevenueGrowth() != null) {
                double growth = f.getRevenueGrowth().doubleValue();
                score += growth >= 15 ? 20 : growth >= 5 ? 8 : growth < 0 ? -18 : -4;
                add(ev, "GROWTH", "Revenue growth", fmt(growth) + "%", "Top-line growth availability", "HIGHER_BETTER", 70, "fundamentals", asOf);
            } else gaps.add("DATA_INCOMPLETE:GROWTH — revenue growth missing");
            if (positive(f.getNetProfitMargin())) score += f.getNetProfitMargin().doubleValue() >= 10 ? 8 : 0;
            if (f.getPegRatio() != null && f.getPegRatio().doubleValue() > 0) score += f.getPegRatio().doubleValue() <= 1.5 ? 7 : -5;
        }
        score += quarterlyTrendAdjustment(trend, ev, asOf, bull, bear, risks, gaps);
        if (score >= 70) bull.add("Growth evidence is constructive.");
        if (score <= 40) bear.add("Growth evidence is weak or deteriorating.");
        return clampScore(score);
    }

    /**
     * Multi-quarter trend contribution to the growth pillar (Phase 7).
     * LOW-confidence trends (< 6 quarters of history) count at half weight.
     */
    private double quarterlyTrendAdjustment(FundamentalTrendService.TrendAnalysis trend,
                                            List<EvidenceItem> ev, LocalDate asOf,
                                            List<String> bull, List<String> bear,
                                            List<String> risks, List<String> gaps) {
        if (trend == null) {
            gaps.add("DATA_INCOMPLETE:QUARTERLY_TREND — fewer than 2 quarters of history");
            return 0;
        }
        double adj = 0;
        if (!Double.isNaN(trend.revenueCagrPct())) {
            double cagr = trend.revenueCagrPct();
            adj += cagr >= 12 ? 8 : cagr >= 4 ? 3 : cagr < 0 ? -8 : 0;
            add(ev, "GROWTH", "Revenue CAGR (quarterly)", fmt(cagr) + "%",
                    "Annualized growth across persisted quarterly history", "HIGHER_BETTER",
                    trendEvidenceConfidence(trend), "quarterly_trends", asOf);
        }
        if (!Double.isNaN(trend.epsCagrPct())) {
            double cagr = trend.epsCagrPct();
            adj += cagr >= 12 ? 6 : cagr < 0 ? -6 : 0;
            add(ev, "GROWTH", "EPS CAGR (quarterly)", fmt(cagr) + "%",
                    "Annualized EPS growth across persisted quarterly history", "HIGHER_BETTER",
                    trendEvidenceConfidence(trend), "quarterly_trends", asOf);
        }
        if (!Double.isNaN(trend.marginTrendSlope())) {
            double slope = trend.marginTrendSlope();
            adj += slope > 0.1 ? 4 : slope < -0.2 ? -5 : 0;
            add(ev, "GROWTH", "Net-margin trend", fmt(slope) + " pp/qtr",
                    "Linear slope of net margin over quarterly history", "HIGHER_BETTER",
                    trendEvidenceConfidence(trend), "quarterly_trends", asOf);
        }
        if (!Double.isNaN(trend.sloanAccrualRatio()) && trend.sloanAccrualRatio() > 0.10) {
            adj -= 8;
            bear.add("Earnings quality flag: accruals exceed operating cash flow (Sloan ratio > 0.10).");
            risks.add("High accruals: reported earnings are not backed by cash and tend to mean-revert.");
            add(ev, "QUALITY", "Sloan accrual ratio", fmt(trend.sloanAccrualRatio()),
                    "(Net income − operating cash flow) / assets; > 0.10 is an earnings-quality red flag",
                    "LOWER_BETTER", trendEvidenceConfidence(trend), "quarterly_trends", asOf);
        }
        if (trend.dilutionComputable() && trend.shareDilution()) {
            adj -= 4;
            bear.add("Share count rose year-over-year — equity dilution drags per-share growth.");
        }
        // LOW confidence (< 6 quarters) — halve the trend's influence
        if (trend.confidence() == org.amit.finwise.cfo.model.QuarterlyFundamentals.TrendConfidence.LOW) {
            adj *= 0.5;
            gaps.add("DATA_INCOMPLETE:QUARTERLY_TREND — LOW confidence (<6 quarters), trend half-weighted");
        }
        return adj;
    }

    private int trendEvidenceConfidence(FundamentalTrendService.TrendAnalysis trend) {
        return switch (trend.confidence()) {
            case HIGH -> 80;
            case MEDIUM -> 70;
            case LOW -> 50;
        };
    }

    private int momentumScore(TechnicalSnapshot t, List<EvidenceItem> ev, LocalDate asOf,
                              List<String> bull, List<String> bear, List<String> risks, List<String> gaps) {
        if (t == null) {
            gaps.add("DATA_INCOMPLETE:MOMENTUM — technical snapshot unavailable");
            return 50;
        }
        double score = 50;
        if (t.trend() == TechnicalSnapshot.Trend.UP) score += 18;
        if (t.trend() == TechnicalSnapshot.Trend.DOWN) score -= 15;
        if (!Double.isNaN(t.rsi14())) {
            score += t.rsi14() > 70 ? -8 : t.rsi14() < 30 ? 5 : 6;
            add(ev, "MOMENTUM", "RSI(14)", fmt(t.rsi14()), "Momentum input only; high RSI alone is not an avoid signal", "BALANCED", 80, "price_history", asOf);
        }
        if (!Double.isNaN(t.return20d())) score += t.return20d() > 0 ? 6 : -6;

        // Cross EVENT is a fresh signal; the alignment STATE is weaker evidence.
        if (t.crossEvent() == TechnicalSnapshot.CrossEvent.GOLDEN_TODAY) {
            score += 8;
            add(ev, "MOMENTUM", "Golden cross", "today",
                    "SMA50 crossed above SMA200 on the latest bar", "POSITIVE", 80, "price_history", asOf);
        } else if (t.crossEvent() == TechnicalSnapshot.CrossEvent.DEATH_TODAY) {
            score -= 8;
            add(ev, "MOMENTUM", "Death cross", "today",
                    "SMA50 crossed below SMA200 on the latest bar", "NEGATIVE", 80, "price_history", asOf);
        } else if (t.goldenCross()) {
            score += 3;
        }

        // Breakout discount: bullish price signals without volume confirmation
        // are unreliable — fade them when volume runs below 70% of its 20d average.
        boolean bullishSignal = t.trend() == TechnicalSnapshot.Trend.UP
                || t.crossEvent() == TechnicalSnapshot.CrossEvent.GOLDEN_TODAY;
        if (bullishSignal && !Double.isNaN(t.volumeRatio()) && t.volumeRatio() < 0.7) {
            score -= 6;
            add(ev, "MOMENTUM", "Volume confirmation", fmt(t.volumeRatio()) + "x avg",
                    "Bullish price action on thin volume (<0.7x 20d average) is discounted",
                    "NEGATIVE", 70, "price_history", asOf);
        }

        if (score >= 70) bull.add("Price trend and momentum are supportive.");
        if (score <= 40) bear.add("Momentum is weak or trend is down.");
        return clampScore(score);
    }

    private int riskScore(TechnicalSnapshot t, StockDeepDive.PortfolioFit fit, StockDeepDive.RiskMetrics riskMetrics,
                          List<EvidenceItem> ev, LocalDate asOf,
                          List<String> bull, List<String> bear, List<String> risks, List<String> gaps) {
        double score = 60;
        if (fit != null && !Double.isNaN(fit.betaVsNifty())) {
            score += fit.betaVsNifty() <= 1.0 ? 10 : fit.betaVsNifty() > 1.5 ? -15 : -4;
            add(ev, "RISK", "Beta vs Nifty", fmt(fit.betaVsNifty()), "Market sensitivity", "LOWER_BETTER", 75, "price_history", asOf);
        } else gaps.add("DATA_INCOMPLETE:RISK — beta unavailable");
        if (t != null && !Double.isNaN(t.realizedVol20dAnn())) {
            score += t.realizedVol20dAnn() <= 25 ? 8 : t.realizedVol20dAnn() > 45 ? -15 : -4;
        }
        if (fit != null && !Double.isNaN(fit.marginalVolImpact())) score += fit.marginalVolImpact() <= 0.02 ? 6 : -10;
        if (riskMetrics != null) {
            if (!Double.isNaN(riskMetrics.maxDrawdown())) {
                score += riskMetrics.maxDrawdown() >= -0.25 ? 6 : riskMetrics.maxDrawdown() < -0.45 ? -12 : -4;
                add(ev, "RISK", "Max drawdown", fmt(riskMetrics.maxDrawdown() * 100) + "%",
                        "Peak-to-trough loss over available adjusted-price history", "HIGHER_BETTER", 75, "price_history", asOf);
            }
            if (!Double.isNaN(riskMetrics.downsideBetaVsNifty())) {
                score += riskMetrics.downsideBetaVsNifty() <= 1.0 ? 5 : riskMetrics.downsideBetaVsNifty() > 1.5 ? -10 : -3;
                add(ev, "RISK", "Downside beta vs Nifty", fmt(riskMetrics.downsideBetaVsNifty()),
                        "Market sensitivity on benchmark down days", "LOWER_BETTER", 75, "price_history", asOf);
            }
            if (!Double.isNaN(riskMetrics.ewmaVolatilityAnn())) {
                double pct = riskMetrics.ewmaVolatilityAnn() * 100;
                score += pct <= 25 ? 5 : pct > 45 ? -8 : -2;
                add(ev, "RISK", "EWMA volatility", fmt(pct) + "%",
                        "RiskMetrics lambda=0.94 annualized volatility", "LOWER_BETTER", 70, "price_history", asOf);
            }
            if (!Double.isNaN(riskMetrics.stressedCorrelation())) {
                score += riskMetrics.stressedCorrelation() <= 0.4 ? 4 : riskMetrics.stressedCorrelation() > 0.75 ? -6 : 0;
                add(ev, "RISK", "Stressed correlation", fmt(riskMetrics.stressedCorrelation()),
                        "Correlation during worst benchmark-return days", "LOWER_BETTER", 70, "price_history", asOf);
            }
            if (!Double.isNaN(riskMetrics.averageTradedValue())) {
                score += riskMetrics.averageTradedValue() >= 100_000_000 ? 5
                        : riskMetrics.averageTradedValue() < 10_000_000 ? -8 : 0;
                add(ev, "LIQUIDITY", "Average traded value", fmt(riskMetrics.averageTradedValue()),
                        "Close times volume liquidity proxy", "HIGHER_BETTER", 65, "price_history", asOf);
            }
        }
        if (score <= 40) risks.add("Risk metrics are elevated versus available benchmark/portfolio evidence.");
        if (score >= 70) bull.add("Risk profile is manageable on available price history.");
        return clampScore(score);
    }

    private int newsMacroScore(String symbol, String sector, List<NewsArticle> news, MacroSnapshot macro,
                               List<EvidenceItem> ev, LocalDate asOf, List<String> bull, List<String> bear,
                               List<String> risks, List<String> gaps) {
        double score = 55;
        if (news == null || news.isEmpty()) {
            gaps.add("DATA_INCOMPLETE:NEWS — no stock-specific news coverage in lookback window");
        } else {
            double weighted = 0;
            double weights = 0;
            for (NewsArticle item : news) {
                // Phase 5b: news magnitude weighting
                double ageWeight = newsAgeWeight(item, asOf);
                double reliability = sourceReliability(item.getSource());
                double actionabilityMult = Math.max(0, item.getActionabilityScore() != null ? item.getActionabilityScore() / 100.0 : 0.5);
                double horizonMult = horizonMultiplier(item.getImpactHorizon());
                double impactMult = impactTypeMultiplier(item.getImpactType());

                // Use fundamental sentiment when available, else price sentiment
                NewsArticle.Sentiment useSentiment = item.getFundamentalSentiment() != null
                        ? item.getFundamentalSentiment()
                        : item.getSentiment() != null ? item.getSentiment() : NewsArticle.Sentiment.NEUTRAL;
                double baseImpact = switch (useSentiment) {
                    case POSITIVE -> 12;
                    case NEGATIVE -> -14;
                    default -> 0;
                };

                // Combined magnitude
                double magnitude = baseImpact * actionabilityMult * horizonMult * impactMult;
                weighted += magnitude * ageWeight * reliability;
                weights += ageWeight * reliability;

                add(ev, "NEWS", "Catalyst impact",
                        item.getImpactType() + "/" + item.getImpactHorizon(),
                        classifyImpactLineItem(item) + " " + useSentiment + " for " + symbol,
                        useSentiment.name(), (int) Math.round(60 + 30 * reliability), item.getSource(), item.getPublishedDate());
            }
            if (weights > 0) score += weighted / weights;
        }
        if (macro == null) {
            gaps.add("DATA_INCOMPLETE:MACRO — macro regime unavailable");
        } else {
            // Multiple regimes can be simultaneously true (e.g. rate tightening +
            // FX stress); sum the sector-adjusted impact of every active one.
            List<String> regimes = activeMacroRegimes(macro);
            int sectorImpact = 0;
            for (String regime : regimes) {
                sectorImpact += macroSectorImpact(regime, sector);
            }
            sectorImpact = Math.max(-15, Math.min(10, sectorImpact));
            score += sectorImpact;
            add(ev, "MACRO", "Macro regime", String.join("+", regimes),
                    "Sector-adjusted composite of all active macro conditions",
                    sectorImpact >= 0 ? "POSITIVE" : "NEGATIVE", 60, "macro_snapshot", asOf);
        }
        if (score >= 65) bull.add("Recent catalysts and macro backdrop are not hostile to the stock.");
        if (score <= 40) {
            bear.add("News or macro catalysts are adverse.");
            risks.add("Catalyst risk: recent events may affect revenue, margins, regulation, flows, or valuation multiple.");
        }
        return clampScore(score);
    }

    private int portfolioFitScore(StockDeepDive.PortfolioFit fit, List<EvidenceItem> ev, LocalDate asOf,
                                  List<String> bull, List<String> bear, List<String> gaps) {
        if (fit == null) return 50;
        double score = 55;
        if (!Double.isNaN(fit.correlationToPortfolio())) score += fit.correlationToPortfolio() < 0.3 ? 20 : fit.correlationToPortfolio() > 0.7 ? -12 : 4;
        else gaps.add("DATA_INCOMPLETE:PORTFOLIO_FIT — correlation unavailable");
        if (!Double.isNaN(fit.marginalVolImpact())) score += fit.marginalVolImpact() <= 0 ? 10 : fit.marginalVolImpact() > 0.03 ? -12 : 0;
        add(ev, "PORTFOLIO_FIT", "Marginal vol impact",
                Double.isNaN(fit.marginalVolImpact()) ? "DATA_INCOMPLETE" : fmt(fit.marginalVolImpact() * 100) + "%",
                "Portfolio fit can help sizing but cannot create a BUY without valuation/quality support", "LOWER_BETTER", 70, "portfolio_risk", asOf);
        if (score >= 70) bull.add("Portfolio fit is diversifying or low impact.");
        if (score <= 40) bear.add("Portfolio fit adds concentration or volatility.");
        return clampScore(score);
    }

    private int confidence(StockFundamentals f, MacroSnapshot macro, List<NewsArticle> news,
                           int priceObs, boolean hasAdjustedPrices, List<String> gaps) {
        int c = 100;
        if (!hasAdjustedPrices) c -= 15;
        if (priceObs < 252) c -= 15;
        if (priceObs < 120) c -= 20;
        if (f == null) c -= 35;
        if (macro == null || macro.getDataQualityNotes() != null) c -= 10;
        if (news == null || news.isEmpty()) c -= 8;
        if (gaps.stream().anyMatch(g -> g.contains("PEER_VALUATION"))) c -= 8;
        if (gaps.stream().anyMatch(g -> g.contains("corporate-action"))) {
            c -= 15;
            c = Math.min(c, 65);
        }
        if (f == null) c = Math.min(c, 60);
        if (priceObs < 120) c = Math.min(c, 50);
        return Math.max(0, Math.min(100, c));
    }

    /**
     * Expected-return band as an actual number where the inputs exist:
     * earnings yield (100/PE) plus clamped revenue growth — the Gordon-growth
     * decomposition of long-run return, ignoring multiple re-rating. A ±3pt
     * band acknowledges the estimate's coarseness. Falls back to the
     * qualitative label when P/E is unavailable.
     */
    private String expectedReturnBand(int valuation, int quality, int growth, StockFundamentals f) {
        int composite = (valuation + quality + growth) / 3;
        if (f != null && positive(f.getTrailingPE())) {
            double earningsYield = 100.0 / f.getTrailingPE().doubleValue();
            if (earningsYield >= 5) composite += 5;
        }
        if (f != null && positive(f.getDividendYield())) composite += Math.min(5, (int) Math.round(f.getDividendYield().doubleValue()));
        String label = composite >= 70 ? "Attractive" : composite >= 50 ? "Fair" : "Poor";

        if (f != null && positive(f.getTrailingPE())) {
            double earningsYield = 100.0 / f.getTrailingPE().doubleValue();
            double growthPct = f.getRevenueGrowth() != null
                    ? Math.max(-5.0, Math.min(15.0, f.getRevenueGrowth().doubleValue()))
                    : 0.0;
            double expected = earningsYield + growthPct;
            return String.format("%.0f–%.0f%% p.a. (earnings yield %.1f%% + growth %.1f%%) — %s",
                    expected - 3, expected + 3, earningsYield, growthPct, label);
        }
        return label;
    }

    private boolean isValueTrap(StockFundamentals f) {
        boolean cheap = "CHEAP".equalsIgnoreCase(nullToEmpty(f.getValuationLabel()))
                || (f.getPeZScore() != null && f.getPeZScore().doubleValue() < -1);
        boolean weakGrowth = f.getRevenueGrowth() != null && f.getRevenueGrowth().doubleValue() < 0;
        boolean weakMargins = f.getNetProfitMargin() != null && f.getNetProfitMargin().doubleValue() <= 0;
        boolean highDebt = f.getDebtToEquity() != null && f.getDebtToEquity().doubleValue() > 2;
        boolean negativeFcf = f.getFreeCashFlow() != null && f.getFreeCashFlow().compareTo(BigDecimal.ZERO) < 0;
        return cheap && (weakGrowth || weakMargins || highDebt || negativeFcf);
    }

    private boolean isFinancialSector(String sector) {
        String s = nullToEmpty(sector).toLowerCase();
        return s.contains("bank") || s.contains("nbfc") || s.contains("financial");
    }

    /**
     * Indian-market sector P/E norms: {cheapBelow, expensiveAbove} on trailing
     * earnings. Nifty 50 itself trades ~20–24x, so a flat US-style "cheap < 18"
     * threshold mislabels most of the Indian market. Financials are valued on
     * P/B and never reach this method.
     */
    private double[] sectorPEBands(String sector) {
        String s = nullToEmpty(sector).toLowerCase();
        if (s.contains("fmcg") || s.contains("consumer staple")) return new double[]{40, 60};
        if (s.contains("it") || s.contains("software") || s.contains("technology")) return new double[]{22, 30};
        if (s.contains("pharma") || s.contains("health")) return new double[]{22, 35};
        if (s.contains("auto")) return new double[]{15, 28};
        if (s.contains("metal") || s.contains("mining") || s.contains("commodit")) return new double[]{8, 15};
        if (s.contains("oil") || s.contains("gas") || s.contains("energy") || s.contains("power")) return new double[]{10, 18};
        if (s.contains("infra") || s.contains("construction") || s.contains("capital good") || s.contains("engineering")) return new double[]{20, 35};
        if (s.contains("cement")) return new double[]{20, 35};
        if (s.contains("telecom")) return new double[]{18, 30};
        return new double[]{20, 30}; // broad-market default (~Nifty trailing range)
    }

    private int bankAssetQualityScore(StockFundamentals f, List<EvidenceItem> ev, LocalDate asOf, List<String> gaps) {
        int score = 0;
        boolean hasAny = false;
        if (f.getNetInterestMargin() != null) {
            hasAny = true;
            double nim = f.getNetInterestMargin().doubleValue();
            score += nim >= 3.5 ? 6 : nim < 2.5 ? -5 : 1;
            add(ev, "QUALITY", "NIM", fmt(nim) + "%", "Bank spread/profitability metric", "HIGHER_BETTER", 80, "bank_fundamentals", asOf);
        }
        if (f.getGrossNpa() != null) {
            hasAny = true;
            double gnpa = f.getGrossNpa().doubleValue();
            score += gnpa <= 3 ? 6 : gnpa > 6 ? -10 : -3;
            add(ev, "QUALITY", "GNPA", fmt(gnpa) + "%", "Gross non-performing assets", "LOWER_BETTER", 80, "bank_fundamentals", asOf);
        }
        if (f.getNetNpa() != null) {
            hasAny = true;
            double nnpa = f.getNetNpa().doubleValue();
            score += nnpa <= 1 ? 6 : nnpa > 3 ? -10 : -3;
            add(ev, "QUALITY", "NNPA", fmt(nnpa) + "%", "Net non-performing assets", "LOWER_BETTER", 80, "bank_fundamentals", asOf);
        }
        if (f.getCasaRatio() != null) {
            hasAny = true;
            double casa = f.getCasaRatio().doubleValue();
            score += casa >= 40 ? 4 : casa < 25 ? -4 : 1;
            add(ev, "QUALITY", "CASA", fmt(casa) + "%", "Low-cost deposit franchise proxy", "HIGHER_BETTER", 75, "bank_fundamentals", asOf);
        }
        if (f.getCreditCost() != null) {
            hasAny = true;
            double creditCost = f.getCreditCost().doubleValue();
            score += creditCost <= 1 ? 4 : creditCost > 2.5 ? -8 : -2;
            add(ev, "QUALITY", "Credit cost", fmt(creditCost) + "%", "Credit-loss pressure", "LOWER_BETTER", 75, "bank_fundamentals", asOf);
        }
        if (f.getProvisionCoverageRatio() != null) {
            hasAny = true;
            double pcr = f.getProvisionCoverageRatio().doubleValue();
            score += pcr >= 70 ? 4 : pcr < 50 ? -5 : 0;
            add(ev, "QUALITY", "Provision coverage", fmt(pcr) + "%", "Coverage against recognized stress", "HIGHER_BETTER", 75, "bank_fundamentals", asOf);
        }
        if (!hasAny) {
            gaps.add("DATA_INCOMPLETE:BANK_ASSET_QUALITY — NIM/GNPA/NNPA/CASA/credit cost unavailable");
        }
        return score;
    }

    private String peerInterpretation(String peerGroup, String metric) {
        String group = peerGroup == null || peerGroup.isBlank() ? "normalized peer group" : peerGroup;
        return metric + " rank versus " + group;
    }

    private double newsAgeWeight(NewsArticle item, LocalDate asOf) {
        if (item.getPublishedDate() == null) return 0.4;
        long age = Math.max(0, ChronoUnit.DAYS.between(item.getPublishedDate(), asOf));
        if (age == 0) return 1.0;
        if (item.getImpactHorizon() == NewsArticle.ImpactHorizon.LONG_TERM) return Math.max(0.5, 1.0 - age * 0.05);
        return Math.max(0.2, 1.0 - age * 0.09);
    }

    private double sourceReliability(String source) {
        String s = nullToEmpty(source).toLowerCase();
        if (s.contains("nse") || s.contains("bse") || s.contains("rbi") || s.contains("sebi")) return 1.0;
        if (s.contains("exchange") || s.contains("official")) return 0.9;
        return 0.7;
    }

    /** Null-safe horizon multiplier — unclassified articles get a middling weight. */
    private double horizonMultiplier(NewsArticle.ImpactHorizon horizon) {
        if (horizon == null) return 0.7;
        return switch (horizon) {
            case SHORT_TERM -> 1.0;
            case MEDIUM_TERM -> 0.8;
            case LONG_TERM -> 0.6;
        };
    }

    /** Null-safe impact-type multiplier — unclassified articles get a middling weight. */
    private double impactTypeMultiplier(NewsArticle.ImpactType type) {
        if (type == null) return 0.5;
        return switch (type) {
            case STOCK -> 1.0;
            case SECTOR -> 0.6;
            case POLICY, REGULATORY -> 0.7;
            case MACRO -> 0.4;
            case MIXED -> 0.6;
        };
    }

    private String classifyImpactLineItem(NewsArticle item) {
        if (item.getCategory() == NewsArticle.Category.REGULATORY || item.getCategory() == NewsArticle.Category.POLICY) return "REGULATORY";
        if (item.getCategory() == NewsArticle.Category.MACRO || item.getCategory() == NewsArticle.Category.MACRO_RISK) return "COST_OF_CAPITAL";
        if (item.getCategory() == NewsArticle.Category.EARNINGS) return "MARGIN";
        if (item.getCategory() == NewsArticle.Category.CORPORATE_ACTION) return "VALUATION_MULTIPLE";
        return "REVENUE";
    }

    /**
     * All macro regimes whose trigger condition currently holds — not just the
     * first. Returns ["RISK_ON"] when nothing adverse (or easing) is active.
     */
    private List<String> activeMacroRegimes(MacroSnapshot macro) {
        List<String> regimes = new ArrayList<>();
        if (macro.getIndiaVix() != null && macro.getIndiaVix().doubleValue() >= 20) regimes.add("RISK_OFF");
        if (macro.getCpiYoY() != null && macro.getCpiYoY().doubleValue() >= 6) regimes.add("INFLATION_PRESSURE");
        if (macro.getUsdInr() != null && macro.getUsdInr().doubleValue() >= 85) regimes.add("FX_STRESS");
        if (macro.getGdpGrowth() != null && macro.getGdpGrowth().doubleValue() < 5) regimes.add("GROWTH_SLOWDOWN");
        if (macro.getRepoRate() != null && macro.getRepoRate().doubleValue() >= 6.5) regimes.add("RATE_TIGHTENING");
        if (macro.getRepoRate() != null && macro.getRepoRate().doubleValue() <= 5.75) regimes.add("RATE_EASING");
        // Phase 6c: yield curve shape (slope in percentage points; 0.5 = 50bp)
        if (macro.getCurveSlope10y3m() != null) {
            double slope = macro.getCurveSlope10y3m().doubleValue();
            if (slope < 0) regimes.add("CURVE_INVERSION");
            else if (slope > 1.5) regimes.add("CURVE_STEEPENING");
        }
        // Phase 6a: sustained FII selling — 5-day cumulative net outflow beyond ₹10,000 Cr
        if (macro.getFiiNetFlow5d() != null && macro.getFiiNetFlow5d().doubleValue() < -10_000) {
            regimes.add("FLOW_PRESSURE");
        }
        if (regimes.isEmpty()) regimes.add("RISK_ON");
        return regimes;
    }

    private int macroSectorImpact(String regime, String sector) {
        String s = nullToEmpty(sector).toLowerCase();
        boolean financial = s.contains("bank") || s.contains("nbfc") || s.contains("financial");
        return switch (regime) {
            case "RISK_OFF" -> -8;
            case "INFLATION_PRESSURE" -> s.contains("fmcg") ? -4 : -7;
            case "RATE_TIGHTENING" -> s.contains("bank") ? 2 : -5;
            case "RATE_EASING" -> s.contains("bank") || s.contains("nbfc") ? 8 : 4;
            case "FX_STRESS" -> s.contains("it") || s.contains("pharma") ? 5 : -5;
            case "GROWTH_SLOWDOWN" -> -6;
            // Inverted curve compresses bank NIMs; steepening expands them
            case "CURVE_INVERSION" -> financial ? -3 : -2;
            case "CURVE_STEEPENING" -> financial ? 2 : 0;
            // FII selling hits high-FII-ownership financials hardest
            case "FLOW_PRESSURE" -> financial ? -5 : -4;
            default -> 4;
        };
    }

    private void add(List<EvidenceItem> ev, String category, String metric, String value, String interpretation,
                     String direction, int confidence, String source, LocalDate asOf) {
        ev.add(new EvidenceItem(category, metric, value, interpretation, direction, confidence, source, asOf));
    }

    private boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private int clampScore(double score) {
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
