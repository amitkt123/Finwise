package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService.TwrrResult;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Hard-truth generators (honesty/compliance workstream): closet indexing, dormant/underperforming
 * holdings, and mutual-fund fee drag. Each generator degrades to "no card" when its source data
 * is unavailable rather than fabricating a number.
 */
@ExtendWith(MockitoExtension.class)
class HardTruthEngineTest {

    @Mock PortfolioPerformanceService performanceService;
    @Mock InvestmentRepository investmentRepository;

    @InjectMocks HardTruthEngine engine;

    @Test
    void generateCards_returnsEmptyList_whenNoDataAvailable() {
        when(performanceService.computeTwrr(any())).thenReturn(Optional.empty());
        when(investmentRepository.findActiveInvestments(any())).thenReturn(List.of());

        List<InsightCard> cards = engine.generateCards("u1");

        assertNotNull(cards);
        assertTrue(cards.isEmpty());
    }

    @Test
    void benchmarkHuggerCard_fires_whenTrackingErrorLow() {
        TwrrResult twrr = new TwrrResult(
                0.10,                       // twrr
                0.10,                       // annualizedTwrr
                LocalDate.of(2025, 1, 1),   // from
                LocalDate.of(2026, 1, 1),   // to
                12,                         // subPeriods
                BigDecimal.valueOf(100_000),// latestValue
                0.095,                      // benchmarkTwrr
                0.095,                      // annualizedBenchmarkTwrr
                0.005,                      // activeReturnAnnualized
                0.015,                      // trackingErrorAnnualized (low → closet indexing)
                0.33                        // informationRatio
        );
        when(performanceService.computeTwrr("u1")).thenReturn(Optional.of(twrr));

        Optional<InsightCard> card = engine.benchmarkHuggerCard("u1");

        assertTrue(card.isPresent());
        assertEquals(InsightCard.Category.SKILL, card.get().category());
        assertEquals(InsightCard.Severity.WATCH, card.get().severity());
    }

    @Test
    void dormantHoldingCards_skip_whenHeldLessThan18Months() {
        Investment recent = Investment.builder()
                .symbol("RECENT")
                .purchaseDate(LocalDate.now().minusMonths(3))
                .quantity(BigDecimal.TEN)
                .costPerUnit(BigDecimal.valueOf(100))
                .currentValue(BigDecimal.valueOf(500))
                .build();

        List<InsightCard> cards = engine.dormantHoldingCards("u1", List.of(recent));

        assertTrue(cards.isEmpty());
    }

    @Test
    void dormantHoldingCards_fire_whenUnderperformingFdOver18Months() {
        Investment dormant = Investment.builder()
                .symbol("DORMANT")
                .purchaseDate(LocalDate.now().minusMonths(24))
                .quantity(BigDecimal.TEN)
                .costPerUnit(BigDecimal.valueOf(100))   // cost basis = 1,000
                .currentValue(BigDecimal.valueOf(1_020))// well below FD-equivalent (~1,132)
                .build();

        List<InsightCard> cards = engine.dormantHoldingCards("u1", List.of(dormant));

        assertEquals(1, cards.size());
        assertEquals(InsightCard.Category.COST, cards.get(0).category());
        assertEquals("DORMANT", cards.get(0).symbol());
    }

    @Test
    void overFeeCard_skip_whenNoMutualFundHoldings() {
        Investment stock = Investment.builder()
                .symbol("HDFCBANK")
                .type(InvestmentType.STOCK)
                .currentValue(BigDecimal.valueOf(500_000))
                .build();

        Optional<InsightCard> card = engine.overFeeCard("u1", List.of(stock));

        assertTrue(card.isEmpty());
    }
}
