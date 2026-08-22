package org.amit.finwise.investment.service;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Hand-computed tests for the three harvesting suggestion classes.
 */
@ExtendWith(MockitoExtension.class)
class TaxHarvestingServiceTest {

    @Mock LotTrackingService lotTrackingService;
    @Mock CapitalGainsTaxService capitalGainsTaxService;
    @Mock InvestmentRepository investmentRepository;
    @InjectMocks TaxHarvestingService service;

    private static final String USER = "u";

    @Test
    void exemptionHarvest_fillsHeadroomWithPartialLotWhenNeeded() {
        // Headroom ₹5,000; long-term lot of 100 units with ₹100 gain/unit (₹10,000 total)
        // → sell 50 units to realize exactly ₹5,000 tax-free.
        mockRates();
        mockHeadroom(5_000);
        mockHoldings(inv("TCS", 200));
        LocalDate longTermBuy = LocalDate.now().minusYears(3);
        mockLots(Map.of("TCS", List.of(lot("TCS", longTermBuy, 100, 100))));

        TaxHarvestingService.HarvestPlan plan = service.suggest(USER);

        assertEquals(1, plan.exemptionHarvest().size());
        TaxHarvestingService.HarvestCandidate c = plan.exemptionHarvest().getFirst();
        assertEquals(50.0, c.unitsToSell(), 1e-9);
        assertEquals(5_000.0, c.ltcgRealized(), 1e-9);
        assertEquals(625.0, c.taxSavedVsLater(), 1e-9, "5,000 × 12.5%");
    }

    @Test
    void boundaryWait_flagsShortTermLotTurningLongTermWithin30Days() {
        // Bought 1 year ago less 20 days, ₹10,000 unrealized gain:
        // waiting 20 days saves 10,000 × (20% − 12.5%) = ₹750
        mockRates();
        mockHeadroom(0);
        mockHoldings(inv("INFY", 1600));
        LocalDate buy = LocalDate.now().minusYears(1).plusDays(20);
        mockLots(Map.of("INFY", List.of(lot("INFY", buy, 100, 1500))));

        TaxHarvestingService.HarvestPlan plan = service.suggest(USER);

        assertTrue(plan.exemptionHarvest().isEmpty(), "short-term lot must not be exemption-harvested");
        assertEquals(1, plan.boundaryWait().size());
        TaxHarvestingService.BoundaryCandidate b = plan.boundaryWait().getFirst();
        assertEquals(20, b.daysToLongTerm());
        assertEquals(10_000.0, b.unrealizedGain(), 1e-9);
        assertEquals(750.0, b.taxSavingIfWaited(), 1e-9);
    }

    @Test
    void lossHarvest_reportsSetOffScopeByHoldingPeriod() {
        mockRates();
        mockHeadroom(125_000);
        mockHoldings(inv("WIPRO", 200), inv("ZEE", 100));
        mockLots(Map.of(
                "WIPRO", List.of(lot("WIPRO", LocalDate.now().minusYears(2), 10, 300)),  // LT loss 1,000
                "ZEE",   List.of(lot("ZEE", LocalDate.now().minusMonths(3), 10, 150)))); // ST loss 500

        TaxHarvestingService.HarvestPlan plan = service.suggest(USER);

        assertEquals(2, plan.lossHarvest().size());
        TaxHarvestingService.LossCandidate biggest = plan.lossHarvest().getFirst();
        assertEquals("WIPRO", biggest.symbol(), "largest loss first");
        assertEquals(1_000.0, biggest.unrealizedLoss(), 1e-9);
        assertTrue(biggest.longTerm());
        assertEquals("offsets LTCG only", biggest.setOffScope());
        assertEquals("offsets STCG first, then LTCG", plan.lossHarvest().get(1).setOffScope());
    }

    @Test
    void noTransactionHistory_disclosesInsteadOfGuessing() {
        mockHeadroom(125_000);
        mockHoldings(inv("TCS", 200));
        mockLots(Map.of());

        TaxHarvestingService.HarvestPlan plan = service.suggest(USER);

        assertTrue(plan.exemptionHarvest().isEmpty());
        assertTrue(plan.notes().stream().anyMatch(n -> n.startsWith("NO_LOT_HISTORY")));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void mockRates() {
        lenient().when(capitalGainsTaxService.stcgRate()).thenReturn(0.20);
        lenient().when(capitalGainsTaxService.ltcgRate()).thenReturn(0.125);
    }

    private void mockHeadroom(double headroom) {
        when(capitalGainsTaxService.realizedTax(org.mockito.ArgumentMatchers.eq(USER),
                org.mockito.ArgumentMatchers.any(LocalDate.class)))
                .thenReturn(new CapitalGainsTaxService.RealizedTaxSummary(
                        LocalDate.parse("2026-04-01"), LocalDate.parse("2027-03-31"),
                        bd(0), bd(0), bd(0), bd(0), bd(0), bd(0),
                        bd(125_000 - headroom), bd(headroom), bd(0), bd(0), List.of(), bd(0), bd(0)));
    }

    private void mockHoldings(Investment... investments) {
        when(investmentRepository.findActiveInvestments(USER)).thenReturn(List.of(investments));
    }

    private void mockLots(Map<String, List<LotTrackingService.HoldingLot>> lots) {
        when(lotTrackingService.buildLedger(USER)).thenReturn(
                new LotTrackingService.LotLedger(lots, List.of(), List.of()));
    }

    private static Investment inv(String symbol, double currentPrice) {
        return Investment.builder()
                .userId(USER).type(InvestmentType.STOCK).symbol(symbol).name(symbol)
                .purchaseDate(LocalDate.now().minusYears(1))
                .currentPrice(BigDecimal.valueOf(currentPrice))
                .build();
    }

    private static LotTrackingService.HoldingLot lot(String sym, LocalDate buyDate,
                                                     double qty, double cost) {
        return new LotTrackingService.HoldingLot(sym, buyDate,
                BigDecimal.valueOf(qty), BigDecimal.valueOf(cost));
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
