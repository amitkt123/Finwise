package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.model.StockScorecard;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockScorecardServiceTest {

    private final StockScorecardService service = new StockScorecardService(null, 62, 6);

    @Test
    void scoreWeighting_buyRequiresHighScoreAndHighConfidence() {
        assertEquals(StockScorecard.Recommendation.BUY,
                service.recommendation(72, 75, 70, 70, 70));
        assertEquals(StockScorecard.Recommendation.WAIT,
                service.recommendation(72, 65, 70, 70, 70));
    }

    @Test
    void missingFundamentals_capsConfidenceAndNeedsMoreDataWhenPriceHistoryIsThin() {
        StockScorecard scorecard = service.build("TEST", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 55, 8, 18),
                null, null, List.of(), null, fit(0.2, -0.01, 0.9),
                riskMetrics(),
                List.of(), 90, false);

        assertTrue(scorecard.confidence() <= 50);
        assertEquals(StockScorecard.Recommendation.NEEDS_MORE_DATA, scorecard.recommendation());
    }

    @Test
    void expensiveHighQualityCompounderIsNotAutomaticallyAvoid() {
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("55"))
                .peZScore(new BigDecimal("1.8"))
                .revenueGrowth(new BigDecimal("22"))
                .operatingMargin(new BigDecimal("28"))
                .netProfitMargin(new BigDecimal("18"))
                .roe(new BigDecimal("24"))
                .debtToEquity(new BigDecimal("0.20"))
                .freeCashFlow(new BigDecimal("1000000"))
                .valuationLabel("EXPENSIVE")
                .build();

        StockScorecard scorecard = service.build("HQ", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 62, 12, 18),
                f, null, List.of(), macro(), fit(0.35, 0.01, 1.05),
                riskMetrics(),
                List.of(), 300, true);

        assertNotEquals(StockScorecard.Recommendation.AVOID, scorecard.recommendation());
        assertTrue(scorecard.qualityScore() >= 70);
        assertTrue(scorecard.growthScore() >= 70);
    }

    @Test
    void cheapDeterioratingBusinessIsNotBuy() {
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("8"))
                .peZScore(new BigDecimal("-1.6"))
                .revenueGrowth(new BigDecimal("-8"))
                .operatingMargin(new BigDecimal("2"))
                .netProfitMargin(new BigDecimal("-3"))
                .roe(new BigDecimal("4"))
                .debtToEquity(new BigDecimal("3.50"))
                .freeCashFlow(new BigDecimal("-1000000"))
                .valuationLabel("CHEAP")
                .build();

        StockScorecard scorecard = service.build("VALUE", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.SIDEWAYS, 48, -3, 35),
                f, null, List.of(), macro(), fit(0.1, 0.0, 1.1),
                riskMetrics(),
                List.of(), 300, true);

        assertNotEquals(StockScorecard.Recommendation.BUY, scorecard.recommendation());
        assertTrue(scorecard.keyRisks().stream().anyMatch(r -> r.toLowerCase().contains("value trap")));
    }

    @Test
    void lowCorrelationFitCannotCreateBuyWithoutValuationQualitySupport() {
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("70"))
                .revenueGrowth(new BigDecimal("1"))
                .operatingMargin(new BigDecimal("4"))
                .roe(new BigDecimal("5"))
                .debtToEquity(new BigDecimal("2.20"))
                .freeCashFlow(new BigDecimal("-10"))
                .valuationLabel("EXPENSIVE")
                .build();

        StockScorecard scorecard = service.build("DIVERSIFIER", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 58, 4, 20),
                f, null, List.of(), macro(), fit(0.05, -0.03, 0.8),
                riskMetrics(),
                List.of(), 300, true);

        assertTrue(scorecard.portfolioFitScore() >= 70);
        assertNotEquals(StockScorecard.Recommendation.BUY, scorecard.recommendation());
    }

    @Test
    void expectedReturnBand_isNumericWhenPeAvailable() {
        // P/E 20 → earnings yield 5%; growth 8% (inside the ±[−5,15] clamp)
        // → expected 13% → band "10–16% p.a."
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("20"))
                .revenueGrowth(new BigDecimal("8"))
                .roe(new BigDecimal("15"))
                .operatingMargin(new BigDecimal("16"))
                .debtToEquity(new BigDecimal("0.6"))
                .freeCashFlow(new BigDecimal("100"))
                .valuationLabel("FAIR")
                .build();

        StockScorecard scorecard = service.build("NUM", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 55, 5, 20),
                f, null, List.of(), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(),
                List.of(), 300, true);

        assertTrue(scorecard.expectedReturnBand().startsWith("10–16% p.a."),
                "band must be a number, got: " + scorecard.expectedReturnBand());
        assertTrue(scorecard.expectedReturnBand().contains("earnings yield 5.0%"));
    }

    @Test
    void highRsiAloneDoesNotProduceAvoid() {
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("22"))
                .revenueGrowth(new BigDecimal("12"))
                .operatingMargin(new BigDecimal("18"))
                .roe(new BigDecimal("16"))
                .debtToEquity(new BigDecimal("0.5"))
                .freeCashFlow(new BigDecimal("1000"))
                .valuationLabel("FAIR")
                .build();

        StockScorecard scorecard = service.build("MOMO", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 78, 18, 22),
                f, null, List.of(), macro(), fit(0.4, 0.01, 1.0),
                riskMetrics(),
                List.of(), 300, true);

        assertNotEquals(StockScorecard.Recommendation.AVOID, scorecard.recommendation());
    }

    @Test
    void scorecardCombinesPeerBankRiskNewsMacroAndPortfolioInputs() {
        StockFundamentals f = baseFundamentals()
                .priceToBook(new BigDecimal("1.4"))
                .peerGroup("private-banks-largecap")
                .peerPbPercentile(new BigDecimal("28"))
                .roe(new BigDecimal("17"))
                .operatingMargin(new BigDecimal("24"))
                .netProfitMargin(new BigDecimal("16"))
                .netInterestMargin(new BigDecimal("3.8"))
                .grossNpa(new BigDecimal("2.1"))
                .netNpa(new BigDecimal("0.6"))
                .casaRatio(new BigDecimal("42"))
                .creditCost(new BigDecimal("0.9"))
                .provisionCoverageRatio(new BigDecimal("73"))
                .revenueGrowth(new BigDecimal("13"))
                .valuationLabel("FAIR")
                .build();

        NewsArticle news = NewsArticle.builder()
                .source("NSE")
                .title("TESTBANK reports stable asset quality")
                .publishedDate(LocalDate.now())
                .sentiment(NewsArticle.Sentiment.POSITIVE)
                .category(NewsArticle.Category.EARNINGS)
                .impactType(NewsArticle.ImpactType.STOCK)
                .impactHorizon(NewsArticle.ImpactHorizon.MEDIUM_TERM)
                .build();

        StockScorecard scorecard = service.build("TESTBANK", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 54, 6, 19),
                f, null, List.of(news), macro(), bankFit(), riskMetrics(),
                List.of(), 300, true);

        assertTrue(scorecard.valuationScore() >= 65);
        assertTrue(scorecard.qualityScore() >= 70);
        assertTrue(scorecard.evidenceItems().stream().anyMatch(e -> e.metricName().contains("Peer P/B")));
        assertTrue(scorecard.evidenceItems().stream().anyMatch(e -> e.metricName().equals("GNPA")));
        assertTrue(scorecard.evidenceItems().stream().anyMatch(e -> e.metricName().equals("EWMA volatility")));
    }

    // ── Phase 5b/5c/7 additions ──────────────────────────────────────────────

    @Test
    void newsWithUnclassifiedImpactFieldsDoesNotThrow() {
        // Regression: impactType / impactHorizon / fundamentalSentiment are all
        // nullable on NewsArticle — Tier-1-only articles arrive unclassified.
        NewsArticle unclassified = NewsArticle.builder()
                .source("rss-feed")
                .title("TEST announces something")
                .publishedDate(LocalDate.now())
                .build();

        StockScorecard scorecard = service.build("TEST", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 55, 5, 20),
                baseFundamentals().trailingPE(new BigDecimal("20")).build(),
                null, List.of(unclassified), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(), List.of(), 300, true);

        assertTrue(scorecard.newsMacroScore() >= 0);
    }

    @Test
    void bullishTrendOnThinVolumeIsDiscountedInMomentum() {
        StockFundamentals f = baseFundamentals().trailingPE(new BigDecimal("20")).build();

        StockScorecard confirmed = service.build("VOL", LocalDate.now(),
                technicalWithVolume(TechnicalSnapshot.Trend.UP, 55, 5, 20, 1.2),
                f, null, List.of(), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(), List.of(), 300, true);
        StockScorecard thin = service.build("VOL", LocalDate.now(),
                technicalWithVolume(TechnicalSnapshot.Trend.UP, 55, 5, 20, 0.5),
                f, null, List.of(), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(), List.of(), 300, true);

        assertEquals(confirmed.momentumScore() - 6, thin.momentumScore(),
                "volumeRatio < 0.7 must discount a bullish trend by 6 points");
    }

    @Test
    void recommendationFlipsOnlyWhenDecisivelyPastBoundary() {
        // BUY boundary on the total score: 62 + 6·ln(70/30) ≈ 67.08
        org.amit.finwise.cfo.repository.StockScorecardSnapshotRepository repo =
                org.mockito.Mockito.mock(org.amit.finwise.cfo.repository.StockScorecardSnapshotRepository.class);
        StockScorecardService svc = new StockScorecardService(repo, 62, 6);

        org.mockito.Mockito.when(repo.findTopBySymbolOrderByScorecardDateDescCreatedAtDesc("X"))
                .thenReturn(java.util.Optional.of(org.amit.finwise.cfo.model.StockScorecardSnapshot.builder()
                        .symbol("X").scorecardDate(LocalDate.now().minusDays(1))
                        .totalScore(66).recommendation(StockScorecard.Recommendation.WAIT)
                        .confidence(80).scorecardJson("{}").build()));

        java.util.List<org.amit.finwise.cfo.model.EvidenceItem> ev = new java.util.ArrayList<>();
        // 68.5 crosses the BUY boundary but not by 3 pts → held at WAIT
        assertEquals(StockScorecard.Recommendation.WAIT,
                svc.applyHysteresis("X", StockScorecard.Recommendation.BUY, 68.5, 70, 70, 70, ev, LocalDate.now()));
        assertTrue(ev.stream().anyMatch(e -> "Hysteresis hold".equals(e.metricName())));
        // 71 is ≥ 3 pts past the boundary → flip allowed
        assertEquals(StockScorecard.Recommendation.BUY,
                svc.applyHysteresis("X", StockScorecard.Recommendation.BUY, 71, 70, 70, 70, ev, LocalDate.now()));
        // Hard AVOID bypasses hysteresis entirely
        assertEquals(StockScorecard.Recommendation.AVOID,
                svc.applyHysteresis("X", StockScorecard.Recommendation.AVOID, 68.5, 70, 20, 70, ev, LocalDate.now()));
    }

    @Test
    void downgradesFromBuyAreAlsoDamped() {
        org.amit.finwise.cfo.repository.StockScorecardSnapshotRepository repo =
                org.mockito.Mockito.mock(org.amit.finwise.cfo.repository.StockScorecardSnapshotRepository.class);
        StockScorecardService svc = new StockScorecardService(repo, 62, 6);

        org.mockito.Mockito.when(repo.findTopBySymbolOrderByScorecardDateDescCreatedAtDesc("Y"))
                .thenReturn(java.util.Optional.of(org.amit.finwise.cfo.model.StockScorecardSnapshot.builder()
                        .symbol("Y").scorecardDate(LocalDate.now().minusDays(1))
                        .totalScore(71).recommendation(StockScorecard.Recommendation.BUY)
                        .confidence(80).scorecardJson("{}").build()));

        java.util.List<org.amit.finwise.cfo.model.EvidenceItem> ev = new java.util.ArrayList<>();
        // 66 dipped below the ~67.08 boundary but not by 3 pts → still BUY
        assertEquals(StockScorecard.Recommendation.BUY,
                svc.applyHysteresis("Y", StockScorecard.Recommendation.WAIT, 66, 70, 70, 70, ev, LocalDate.now()));
        // 63 is decisively below → downgrade goes through
        assertEquals(StockScorecard.Recommendation.WAIT,
                svc.applyHysteresis("Y", StockScorecard.Recommendation.WAIT, 63, 70, 70, 70, ev, LocalDate.now()));
    }

    @Test
    void sloanAccrualRedFlagDragsGrowthAndSurfacesRisk() {
        StockFundamentals f = baseFundamentals()
                .trailingPE(new BigDecimal("20"))
                .revenueGrowth(new BigDecimal("10"))
                .build();
        FundamentalTrendService.TrendAnalysis flagged = new FundamentalTrendService.TrendAnalysis(
                8.0, 7.0, 0.0, 0.18, false, false,
                org.amit.finwise.cfo.model.QuarterlyFundamentals.TrendConfidence.HIGH, List.of());
        FundamentalTrendService.TrendAnalysis clean = new FundamentalTrendService.TrendAnalysis(
                8.0, 7.0, 0.0, 0.02, false, false,
                org.amit.finwise.cfo.model.QuarterlyFundamentals.TrendConfidence.HIGH, List.of());

        StockScorecard withFlag = service.build("ACCR", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 55, 5, 20),
                f, flagged, List.of(), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(), List.of(), 300, true);
        StockScorecard withoutFlag = service.build("ACCR", LocalDate.now(),
                technical(TechnicalSnapshot.Trend.UP, 55, 5, 20),
                f, clean, List.of(), macro(), fit(0.3, 0.01, 1.0),
                riskMetrics(), List.of(), 300, true);

        assertEquals(withoutFlag.growthScore() - 8, withFlag.growthScore(),
                "Sloan > 0.10 must cost the growth pillar 8 points at HIGH confidence");
        assertTrue(withFlag.keyRisks().stream().anyMatch(r -> r.toLowerCase().contains("accrual")));
    }

    private StockFundamentals.StockFundamentalsBuilder baseFundamentals() {
        return StockFundamentals.builder()
                .symbol("TEST")
                .snapshotDate(LocalDate.now())
                .isProfitable(true)
                .dividendYield(BigDecimal.ONE);
    }

    private org.amit.finwise.cfo.model.MacroSnapshot macro() {
        return org.amit.finwise.cfo.model.MacroSnapshot.builder()
                .snapshotDate(LocalDate.now())
                .repoRate(new BigDecimal("6.25"))
                .cpiYoY(new BigDecimal("4.5"))
                .indiaVix(new BigDecimal("13"))
                .usdInr(new BigDecimal("83"))
                .gdpGrowth(new BigDecimal("6.2"))
                .build();
    }

    private StockDeepDive.PortfolioFit fit(double correlation, double deltaVol, double beta) {
        return new StockDeepDive.PortfolioFit(false, 0, beta, Double.NaN, "IT",
                "not held", correlation, deltaVol, 0.20, 0.05,
                "NEEDS-MORE-DATA", "legacy verdict ignored by scorecard");
    }

    private StockDeepDive.PortfolioFit bankFit() {
        return new StockDeepDive.PortfolioFit(false, 0, 0.9, Double.NaN, "Banking",
                "not held", 0.25, 0.0, 0.20, 0.05,
                "NEEDS-MORE-DATA", "legacy verdict ignored by scorecard");
    }

    private StockDeepDive.RiskMetrics riskMetrics() {
        return new StockDeepDive.RiskMetrics(-0.18, 0.95, 0.22, 0.45, 150_000_000, 260);
    }

    private TechnicalSnapshot technical(TechnicalSnapshot.Trend trend, double rsi, double ret20d, double vol) {
        return technicalWithVolume(trend, rsi, ret20d, vol, 1.2);
    }

    private TechnicalSnapshot technicalWithVolume(TechnicalSnapshot.Trend trend, double rsi, double ret20d,
                                                  double vol, double volumeRatio) {
        return new TechnicalSnapshot("TEST", LocalDate.now(), 100,
                95, 90, 85, 95, 90, 85,
                5, 10, 15, rsi, 2, 2, vol,
                110, 80, -9, 25, 2, ret20d,
                trend == TechnicalSnapshot.Trend.UP, TechnicalSnapshot.CrossEvent.NONE, -1,
                1, 0.5, 0.5,
                0.5, 0.1,
                100000, 95, volumeRatio,
                trend, rsi > 70 ? TechnicalSnapshot.Momentum.OVERBOUGHT : TechnicalSnapshot.Momentum.NEUTRAL,
                true, 260);
    }
}
