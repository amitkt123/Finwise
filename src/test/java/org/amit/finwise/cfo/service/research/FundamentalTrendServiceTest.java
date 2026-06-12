package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.QuarterlyFundamentals;
import org.amit.finwise.cfo.repository.QuarterlyFundamentalsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FundamentalTrendServiceTest {

    @Mock
    QuarterlyFundamentalsRepository quarterlyRepo;

    /**
     * 9 quarters of revenue compounding 5% per quarter:
     * span = 8 quarters = 2 years → CAGR = 1.05⁴ − 1 = 21.550625% exactly.
     */
    @Test
    void revenueCagr_isAnnualizedOverTheSpanNotTheCount() {
        FundamentalTrendService service = new FundamentalTrendService(quarterlyRepo);
        List<QuarterlyFundamentals> quarters = new ArrayList<>();
        double revenue = 100;
        for (int i = 0; i < 9; i++) {
            quarters.add(quarter(LocalDate.of(2024, 3, 31).plusMonths(3L * i),
                    revenue, 10, null, null, null));
            revenue *= 1.05;
        }
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("T")).thenReturn(descending(quarters));

        FundamentalTrendService.TrendAnalysis trend = service.analyzeTrend("T", LocalDate.now()).orElseThrow();
        assertEquals(21.550625, trend.revenueCagrPct(), 1e-9, "1.05^4 − 1, annualized over 2 years");
        assertEquals(QuarterlyFundamentals.TrendConfidence.HIGH, trend.confidence());
    }

    /**
     * Sloan over the trailing 4 quarters: NI = 12, CFO = 5 each quarter,
     * assets constant 200 → (4·7) / 200 = 0.14 → red-flag territory.
     */
    @Test
    void sloanAccrualRatio_usesTrailingFourQuartersOverAverageAssets() {
        FundamentalTrendService service = new FundamentalTrendService(quarterlyRepo);
        List<QuarterlyFundamentals> quarters = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            quarters.add(quarter(LocalDate.of(2024, 3, 31).plusMonths(3L * i),
                    100, 12, 5.0, 200.0, null));
        }
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("T")).thenReturn(descending(quarters));

        FundamentalTrendService.TrendAnalysis trend = service.analyzeTrend("T", LocalDate.now()).orElseThrow();
        assertEquals(0.14, trend.sloanAccrualRatio(), 1e-12);
    }

    /**
     * Net margin rising exactly 1pp per quarter (NI 10,11,12,13 on revenue 100)
     * → least-squares slope = 1.0 pp/quarter exactly.
     */
    @Test
    void marginTrendSlope_matchesHandComputedRegression() {
        FundamentalTrendService service = new FundamentalTrendService(quarterlyRepo);
        List<QuarterlyFundamentals> quarters = new ArrayList<>();
        double[] netIncomes = {10, 11, 12, 13};
        for (int i = 0; i < 4; i++) {
            quarters.add(quarter(LocalDate.of(2024, 3, 31).plusMonths(3L * i),
                    100, netIncomes[i], null, null, null));
        }
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("T")).thenReturn(descending(quarters));

        FundamentalTrendService.TrendAnalysis trend = service.analyzeTrend("T", LocalDate.now()).orElseThrow();
        assertEquals(1.0, trend.marginTrendSlope(), 1e-12);
        assertEquals(QuarterlyFundamentals.TrendConfidence.LOW, trend.confidence(), "4 quarters is LOW confidence");
    }

    @Test
    void dilution_onlyComputableWhenShareCountsExistAcrossYoYWindow() {
        FundamentalTrendService service = new FundamentalTrendService(quarterlyRepo);

        // Shares grow 100 → 110 across 5 quarters → dilution detected
        List<QuarterlyFundamentals> diluted = new ArrayList<>();
        double[] shares = {100, 102, 105, 108, 110};
        for (int i = 0; i < 5; i++) {
            diluted.add(quarter(LocalDate.of(2024, 3, 31).plusMonths(3L * i),
                    100, 10, null, null, shares[i]));
        }
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("T")).thenReturn(descending(diluted));
        FundamentalTrendService.TrendAnalysis trend = service.analyzeTrend("T", LocalDate.now()).orElseThrow();
        assertTrue(trend.dilutionComputable());
        assertTrue(trend.shareDilution());

        // No share data at all → not computable, never auto-passes
        List<QuarterlyFundamentals> noShares = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            noShares.add(quarter(LocalDate.of(2024, 3, 31).plusMonths(3L * i),
                    100, 10, null, null, null));
        }
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("N")).thenReturn(descending(noShares));
        FundamentalTrendService.TrendAnalysis noShareTrend = service.analyzeTrend("N", LocalDate.now()).orElseThrow();
        assertFalse(noShareTrend.dilutionComputable());
        assertFalse(noShareTrend.shareDilution());
    }

    @Test
    void fewerThanTwoQuarters_yieldsEmpty() {
        FundamentalTrendService service = new FundamentalTrendService(quarterlyRepo);
        when(quarterlyRepo.findBySymbolOrderByQuarterEndDesc("T"))
                .thenReturn(List.of(quarter(LocalDate.of(2024, 3, 31), 100, 10, null, null, null)));
        Optional<FundamentalTrendService.TrendAnalysis> trend = service.analyzeTrend("T", LocalDate.now());
        assertTrue(trend.isEmpty());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private QuarterlyFundamentals quarter(LocalDate end, double revenue, double netIncome,
                                          Double cfo, Double assets, Double shares) {
        return QuarterlyFundamentals.builder()
                .symbol("T")
                .quarterEnd(end)
                .revenue(BigDecimal.valueOf(revenue))
                .netIncome(BigDecimal.valueOf(netIncome))
                .operatingCashFlow(cfo != null ? BigDecimal.valueOf(cfo) : null)
                .totalAssets(assets != null ? BigDecimal.valueOf(assets) : null)
                .sharesOutstanding(shares != null ? BigDecimal.valueOf(shares) : null)
                .build();
    }

    /** Repository contract returns newest-first; the service re-sorts ascending. */
    private List<QuarterlyFundamentals> descending(List<QuarterlyFundamentals> ascending) {
        List<QuarterlyFundamentals> out = new ArrayList<>(ascending);
        out.sort((a, b) -> b.getQuarterEnd().compareTo(a.getQuarterEnd()));
        return List.copyOf(out); // immutable, like a real JPA result must be treated
    }
}
