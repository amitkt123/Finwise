package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Wiring tests for PortfolioRiskService data-quality semantics: beta imputation,
 * excluded-weight reporting, and the Cornish-Fisher validity fallback — plus
 * known-answer tests for the interpolated empirical quantile.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioRiskServiceTest {

    @Mock ReturnSeriesService returnSeriesService;
    @Mock InvestmentRepository investmentRepository;
    @Mock LiquidityService liquidityService;

    private PortfolioRiskService service;

    private static final String USER = "u";
    private static final int N = 70; // ≥ MIN_OBSERVATIONS aligned days

    @BeforeEach
    void setUp() {
        RiskProperties riskProperties = new RiskProperties();
        QuantitativeMacroState macroState = mock(QuantitativeMacroState.class);
        // lenient: interpolatedQuantile_* tests call static methods without using compute()
        lenient().when(macroState.getRiskFreeRate()).thenReturn(riskProperties.getRiskFreeRate());
        service = new PortfolioRiskService(
                returnSeriesService, new CovarianceEngine(), investmentRepository, riskProperties,
                macroState, new GarchService(riskProperties), liquidityService);
    }

    @Test
    void betaWithoutBenchmarkOverlap_isImputedAsMarketBetaOne() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 60_000), inv("BBB", 40_000)));

        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> ((i % 7) - 3) * 0.005));
        returns.put("BBB", series(d, i -> ((i % 5) - 2) * 0.004));
        // Benchmark has only 30 overlapping days — below MIN_OBSERVATIONS, so
        // betaStats returns NaN for both holdings.
        returns.put(StockPriceService.NIFTY_SYMBOL, series(dates(30), i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        RiskDecomposition rd = service.compute(USER).orElseThrow();

        assertEquals(1.0, rd.perHoldingBeta().get("AAA"), 1e-12, "NaN beta must default to 1.0, not 0.0");
        assertEquals(1.0, rd.perHoldingBeta().get("BBB"), 1e-12);
        assertEquals(1.0, rd.portfolioBeta(), 1e-12, "w_A·1 + w_B·1 = 1.0");
        assertTrue(rd.dataQualityNotes().stream().anyMatch(n -> n.startsWith("BETA_IMPUTED: AAA")),
                "imputation must be disclosed, got " + rd.dataQualityNotes());
    }

    @Test
    void excludedSymbolWeight_isReportedBeforeRenormalization() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 60_000), inv("BBB", 40_000), inv("CCC", 30_000)));

        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> ((i % 7) - 3) * 0.005));
        returns.put("BBB", series(d, i -> ((i % 5) - 2) * 0.004));
        // CCC has no return series at all (insufficient history)
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        RiskDecomposition rd = service.compute(USER).orElseThrow();

        assertEquals(List.of("CCC"), rd.excludedSymbols());
        assertEquals(30_000.0 / 130_000.0, rd.excludedWeightPct(), 1e-12,
                "excluded weight must be the raw pre-normalization share");
        assertTrue(rd.isLowConfidence());
        assertTrue(rd.dataQualityNotes().stream().anyMatch(n -> n.startsWith("EXCLUDED_WEIGHT")),
                "exclusion must be disclosed, got " + rd.dataQualityNotes());
    }

    @Test
    void extremeMoments_substituteParametricVarForCornishFisher() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 50_000), inv("BBB", 50_000)));

        // 69 tiny positive days and one −35% crash: sample skew ≈ −8, exKurt ≫ 10 —
        // far outside the Cornish-Fisher validity domain.
        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> i == 30 ? -0.35 : 0.002));
        returns.put("BBB", series(d, i -> i == 30 ? -0.35 : 0.002));
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        Optional<RiskDecomposition> rdOpt = service.compute(USER);
        RiskDecomposition rd = rdOpt.orElseThrow();

        assertTrue(Math.abs(rd.returnSkewness()) > 2.5, "fixture must be outside the domain");
        assertEquals(rd.var95Parametric(), rd.var95CornishFisher(), 1e-9,
                "invalid CF expansion must fall back to the parametric figure");
        assertEquals(rd.var99Parametric(), rd.var99CornishFisher(), 1e-9);
        assertTrue(rd.dataQualityNotes().stream().anyMatch(n -> n.startsWith("CF_INVALID")),
                "fallback must be disclosed, got " + rd.dataQualityNotes());
    }

    @Test
    void shrinkage_appliesAtThreeHoldingsButNotTwo() {
        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> ((i % 7) - 3) * 0.005));
        returns.put("BBB", series(d, i -> ((i % 5) - 2) * 0.004));
        returns.put("CCC", series(d, i -> ((i % 4) - 1.5) * 0.006));
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 50_000), inv("BBB", 30_000), inv("CCC", 20_000)));
        RiskDecomposition withThree = service.compute(USER).orElseThrow();
        assertNotNull(withThree.shrinkageIntensity(), "N=3 must go through Ledoit-Wolf");
        assertTrue(withThree.shrinkageIntensity() >= 0.0 && withThree.shrinkageIntensity() <= 1.0);

        // With 2 holdings the single off-diagonal is estimated directly — no shrinkage
        Map<String, NavigableMap<LocalDate, Double>> twoSymbolReturns = new LinkedHashMap<>();
        twoSymbolReturns.put("AAA", returns.get("AAA"));
        twoSymbolReturns.put("BBB", returns.get("BBB"));
        twoSymbolReturns.put(StockPriceService.NIFTY_SYMBOL, returns.get(StockPriceService.NIFTY_SYMBOL));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(twoSymbolReturns);
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 60_000), inv("BBB", 40_000)));
        RiskDecomposition withTwo = service.compute(USER).orElseThrow();
        assertNull(withTwo.shrinkageIntensity());
    }

    @Test
    void maxDrawdown_isZeroForMonotonicUpSeriesAndCalmarFollowsReturn() {
        // Both holdings rise every day (constant +0.4% daily) → no peak-to-trough → MDD 0.
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 50_000), inv("BBB", 50_000)));
        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> 0.004));
        returns.put("BBB", series(d, i -> 0.004));
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        RiskDecomposition rd = service.compute(USER).orElseThrow();
        assertEquals(0.0, rd.maxDrawdown(), 1e-12, "monotonic-up series has no drawdown");
        assertTrue(Double.isNaN(rd.calmarRatio()), "Calmar is undefined when drawdown is 0");
    }

    @Test
    void maxDrawdown_capturesKnownPeakToTrough() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 50_000), inv("BBB", 50_000)));
        // Up for 10 days, then a single −20% day, then flat. The value path peaks just
        // before the crash and the trough is the crash day → MDD ≈ −20%.
        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> i == 20 ? -0.20 : (i < 20 ? 0.003 : 0.0)));
        returns.put("BBB", series(d, i -> i == 20 ? -0.20 : (i < 20 ? 0.003 : 0.0)));
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        RiskDecomposition rd = service.compute(USER).orElseThrow();
        assertEquals(-0.20, rd.maxDrawdown(), 1e-9, "single −20% day defines the drawdown");
        assertFalse(Double.isNaN(rd.calmarRatio()), "Calmar must be finite when drawdown < 0");
    }

    @Test
    void interpolatedQuantile_matchesR7HandComputation() {
        // T=20, values 1..20, p=0.05: h = 19×0.05 = 0.95 → 1 + 0.95×(2−1) = 1.95
        double[] sorted = new double[20];
        for (int i = 0; i < 20; i++) sorted[i] = i + 1;
        assertEquals(1.95, PortfolioRiskService.interpolatedQuantile(sorted, 0.05), 1e-12);

        // T=21, p=0.05: h = 20×0.05 = 1.0 exactly → the 2nd order statistic
        double[] sorted21 = new double[21];
        for (int i = 0; i < 21; i++) sorted21[i] = (i + 1) * 10;
        assertEquals(20.0, PortfolioRiskService.interpolatedQuantile(sorted21, 0.05), 1e-12);
    }

    @Test
    void interpolatedQuantile_handlesDegenerateInputs() {
        assertTrue(Double.isNaN(PortfolioRiskService.interpolatedQuantile(new double[0], 0.05)));
        assertEquals(7.0, PortfolioRiskService.interpolatedQuantile(new double[]{7.0}, 0.05), 1e-12);
        double[] sorted = {1, 2, 3};
        assertEquals(3.0, PortfolioRiskService.interpolatedQuantile(sorted, 1.0), 1e-12);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Investment inv(String symbol, double currentValue) {
        return Investment.builder()
                .userId(USER)
                .symbol(symbol)
                .name(symbol)
                .currentValue(BigDecimal.valueOf(currentValue))
                .build();
    }

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) d[i] = start.plusDays(i);
        return d;
    }

    private interface ReturnAt { double value(int i); }

    private static NavigableMap<LocalDate, Double> series(LocalDate[] d, ReturnAt f) {
        NavigableMap<LocalDate, Double> m = new TreeMap<>();
        for (int i = 0; i < d.length; i++) m.put(d[i], f.value(i));
        return m;
    }
}
