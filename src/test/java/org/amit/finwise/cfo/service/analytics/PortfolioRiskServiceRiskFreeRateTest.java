package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.config.RiskProperties;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.macro.QuantitativeMacroState;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
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
import java.util.TreeMap;
import java.util.function.IntToDoubleFunction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Wiring test: verifies that PortfolioRiskService.compute() fetches the risk-free
 * rate from QuantitativeMacroState (live source) rather than the static RiskProperties
 * config value.
 */
@ExtendWith(MockitoExtension.class)
class PortfolioRiskServiceRiskFreeRateTest {

    @Mock ReturnSeriesService returnSeriesService;
    @Mock InvestmentRepository investmentRepository;
    @Mock QuantitativeMacroState macroState;
    @Mock LiquidityService liquidityService;

    private static final String USER = "u";
    private static final int N = 70; // >= MIN_OBSERVATIONS

    @Test
    void sharpeUsesLiveRateNotStaticConfig() {
        // macroState provides live rate 0.068; static riskProperties would give 0.065.
        // Wiring test: verify getRiskFreeRate() is actually called during compute().
        RiskProperties riskProperties = new RiskProperties();
        when(macroState.getRiskFreeRate()).thenReturn(0.068);

        PortfolioRiskService service = new PortfolioRiskService(
                returnSeriesService, new CovarianceEngine(), investmentRepository,
                riskProperties, macroState, new GarchService(riskProperties), liquidityService);

        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("AAA", 60_000), inv("BBB", 40_000)));

        LocalDate[] d = dates(N);
        Map<String, NavigableMap<LocalDate, Double>> returns = new LinkedHashMap<>();
        returns.put("AAA", series(d, i -> ((i % 7) - 3) * 0.005));
        returns.put("BBB", series(d, i -> ((i % 5) - 2) * 0.004));
        returns.put(StockPriceService.NIFTY_SYMBOL, series(d, i -> ((i % 3) - 1) * 0.003));
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class))).thenReturn(returns);

        service.compute(USER);

        verify(macroState, atLeastOnce()).getRiskFreeRate();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static Investment inv(String symbol, double currentValue) {
        return Investment.builder()
                .userId(USER).symbol(symbol).name(symbol)
                .currentValue(BigDecimal.valueOf(currentValue)).build();
    }

    private static LocalDate[] dates(int n) {
        LocalDate[] d = new LocalDate[n];
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < n; i++) d[i] = start.plusDays(i);
        return d;
    }

    private static NavigableMap<LocalDate, Double> series(LocalDate[] d, IntToDoubleFunction f) {
        NavigableMap<LocalDate, Double> m = new TreeMap<>();
        for (int i = 0; i < d.length; i++) m.put(d[i], f.applyAsDouble(i));
        return m;
    }
}
