package org.amit.finwise.investment.service;

import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Hand-computed rupee tests for §55(2)(ac) grandfathering, the debt-MF slab
 * regime, and realized-gain loss netting.
 */
@ExtendWith(MockitoExtension.class)
class CapitalGainsTaxServiceTest {

    @Mock StockPriceService stockPriceService;
    @Mock LotTrackingService lotTrackingService;

    private CapitalGainsTaxService service;

    private static final String USER = "u";

    @BeforeEach
    void setUp() {
        service = new CapitalGainsTaxService(stockPriceService, lotTrackingService,
                0.20, 0.125, 125_000, 0.30, "2018-01-31", 40_000, 0.10, 0.20, "2012-04-01", 0.125, 24);
    }

    // ── Grandfathering (canonical §55(2)(ac) worked example) ─────────────────

    @Test
    void grandfathering_fmvAboveSalePrice_capsCostAtSalePrice_gainZero() {
        // Bought 2016 @100, FMV 31-Jan-2018 = 500, current 400:
        // cost = max(100, min(500, 400)) = 400 → gain 0
        when(stockPriceService.closeOn("LEGACY", LocalDate.parse("2018-01-31")))
                .thenReturn(Optional.of(BigDecimal.valueOf(500)));

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(
                legacy("LEGACY", 100, 400, 10)));

        assertEquals(0.0, est.ltcgGains().doubleValue(), 1e-9);
        assertEquals(0.0, est.totalTaxIfSoldToday().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("GRANDFATHERED: LEGACY")));
    }

    @Test
    void grandfathering_salePriceAboveFmv_costStepsUpToFmv() {
        // Bought 2016 @100, FMV = 500, current 600:
        // cost = max(100, min(500, 600)) = 500 → gain (600−500)×10 = 1000
        when(stockPriceService.closeOn("LEGACY", LocalDate.parse("2018-01-31")))
                .thenReturn(Optional.of(BigDecimal.valueOf(500)));

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(
                legacy("LEGACY", 100, 600, 10)));

        assertEquals(1000.0, est.ltcgGains().doubleValue(), 1e-9);
    }

    @Test
    void grandfathering_fmvUnavailable_usesRawGainWithDisclosure() {
        when(stockPriceService.closeOn("LEGACY", LocalDate.parse("2018-01-31")))
                .thenReturn(Optional.empty());

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(
                legacy("LEGACY", 100, 600, 10)));

        assertEquals(5000.0, est.ltcgGains().doubleValue(), 1e-9, "raw gain (600−100)×10");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("GRANDFATHERING_SKIPPED")));
    }

    // ── Mutual-fund regime split ─────────────────────────────────────────────

    @Test
    void debtFund_isTaxedAtSlabRateRegardlessOfHoldingPeriod() {
        Investment debtMf = Investment.builder()
                .userId(USER).type(InvestmentType.MUTUAL_FUND)
                .name("ABC Corporate Bond Fund - Direct Growth")
                .purchaseDate(LocalDate.now().minusYears(3))
                .quantity(BigDecimal.valueOf(100))
                .costPerUnit(BigDecimal.valueOf(10))
                .currentPrice(BigDecimal.valueOf(15))
                .unrealizedGainLoss(BigDecimal.valueOf(500))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(debtMf));

        assertEquals(500.0, est.slabGains().doubleValue(), 1e-9);
        assertEquals(150.0, est.slabTaxIfSoldToday().doubleValue(), 1e-9, "500 × 30% slab");
        assertEquals(0.0, est.ltcgGains().doubleValue(), 1e-9, "debt MF must not enter the equity LTCG bucket");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("MF_CLASSIFIED_DEBT")));
    }

    @Test
    void equityFund_staysInEquityRegimeWithAssumptionNote() {
        Investment equityMf = Investment.builder()
                .userId(USER).type(InvestmentType.MUTUAL_FUND)
                .name("XYZ Flexi Cap Fund - Direct Growth")
                .purchaseDate(LocalDate.now().minusMonths(6))
                .unrealizedGainLoss(BigDecimal.valueOf(1000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(equityMf));

        assertEquals(1000.0, est.stcgGains().doubleValue(), 1e-9);
        assertEquals(200.0, est.stcgTaxIfSoldToday().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("MF_ASSUMED_EQUITY")));
    }

    // ── PPF exemption ─────────────────────────────────────────────────────────

    @Test
    void ppf_isFullyExemptAndReportedNotDropped() {
        Investment ppf = Investment.builder()
                .userId(USER).type(InvestmentType.PPF).name("PPF Account")
                .purchaseDate(LocalDate.now().minusYears(5))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(500_000))
                .totalCost(BigDecimal.valueOf(500_000)).currentValue(BigDecimal.valueOf(650_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(ppf));

        assertEquals(650_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.exclusions().isEmpty(), "PPF must not be silently excluded");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("PPF_EXEMPT")));
        assertEquals(0.0, est.totalTaxIfSoldToday().doubleValue(), 1e-9);
    }

    @Test
    void ppf_missingCurrentValue_fallsBackToTotalCostWithDisclosure() {
        Investment ppf = Investment.builder()
                .userId(USER).type(InvestmentType.PPF).name("PPF Account")
                .purchaseDate(LocalDate.now().minusYears(5))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(500_000))
                .totalCost(BigDecimal.valueOf(500_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(ppf));

        assertEquals(500_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("EXEMPT_VALUE_ASSUMED_FROM_COST")));
    }

    // ── Interest income (FD / post office) ──────────────────────────────────

    @Test
    void fixedDeposit_computesAnnualInterestAtSlabRate() {
        Investment fd = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD")
                .purchaseDate(LocalDate.now().minusYears(1))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(100_000))
                .interestRate(BigDecimal.valueOf(7))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(fd));

        assertEquals(7_000.0, est.interestIncomeGains().doubleValue(), 1e-9, "100,000 × 7%");
        assertEquals(2_100.0, est.interestIncomeTax().doubleValue(), 1e-9, "7,000 × 30% slab");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INTEREST_SIMPLE_ANNUAL_ASSUMED")));
    }

    @Test
    void postOfficeScheme_missingInterestRate_degradesGracefullyWithNote() {
        Investment nsc = Investment.builder()
                .userId(USER).type(InvestmentType.POST_OFFICE_SCHEME).name("NSC")
                .purchaseDate(LocalDate.now().minusYears(1))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(nsc));

        assertEquals(0.0, est.interestIncomeGains().doubleValue(), 1e-9);
        assertTrue(est.exclusions().contains("NSC"));
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INTEREST_RATE_MISSING")));
    }

    @Test
    void interestIncome_sameHighPlatformInterest_flagsLikelyTds() {
        Investment fd1 = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD 1")
                .purchaseDate(LocalDate.now().minusYears(1)).platform("HDFC Bank")
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(300_000))
                .interestRate(BigDecimal.valueOf(7)).build();
        Investment fd2 = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD 2")
                .purchaseDate(LocalDate.now().minusYears(1)).platform("HDFC Bank")
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(300_000))
                .interestRate(BigDecimal.valueOf(7)).build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(fd1, fd2));

        // 300,000×7% × 2 = 42,000 from the same platform, over the 40,000 TDS threshold
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("TDS_LIKELY") && n.contains("HDFC Bank")));
    }

    // ── Insurance (Section 10(10D)) ──────────────────────────────────────────

    @Test
    void insurance_premiumWithinTenPercentThreshold_isExempt() {
        // premium 40,000 / sumAssured 500,000 = 8% ≤ 10% threshold (post-2012 policy)
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("LIC Term Plan")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(600_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(40_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(600_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_EXEMPT_10_10D")));
    }

    @Test
    void insurance_premiumExceedsTenPercentThreshold_isTaxedAtSlabRate() {
        // premium 80,000 / sumAssured 500,000 = 16% > 10% threshold (post-2012 policy)
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("ULIP Growth")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(600_000))
                .unrealizedGainLoss(BigDecimal.valueOf(200_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(80_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(200_000.0, est.slabGains().doubleValue(), 1e-9);
        assertEquals(60_000.0, est.slabTaxIfSoldToday().doubleValue(), 1e-9, "200,000 × 30% slab");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_TAXABLE_10_10D")));
    }

    @Test
    void insurance_prePost2012Threshold_isTwentyPercentNotTen() {
        // premium 90,000 / sumAssured 500,000 = 18% — exempt under the pre-2012 20% rule,
        // would have been taxable under the post-2012 10% rule
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("Old LIC Endowment")
                .purchaseDate(LocalDate.parse("2010-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(550_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(90_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(550_000.0, est.exemptAmount().doubleValue(), 1e-9);
    }

    @Test
    void insurance_missingSumAssured_assumedExemptWithDisclosure() {
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("Unknown Policy")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(500_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(500_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_ASSUMED_EXEMPT_10_10D")));
    }

    // ── Non-equity flat-rate assets (gold / bond / commodity) ────────────────

    @Test
    void gold_longTerm_flatRateNoIndexation() {
        // Held 25 months (> 24-month LT threshold), gain 100,000 × 12.5% flat = 12,500
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold")
                .purchaseDate(LocalDate.now().minusMonths(25))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(100_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(12_500.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "100,000 × 12.5% flat, no indexation");
    }

    @Test
    void gold_shortTerm_taxedAtSlabRateNotFlatRate() {
        // Held 23 months (< 24-month LT threshold) → slab rate, not the 12.5% LT rate
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold")
                .purchaseDate(LocalDate.now().minusMonths(23))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(30_000.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "100,000 × 30% slab, short-term");
    }

    @Test
    void bondAndCommodity_alsoUseNonEquityFlatRegime() {
        Investment bond = Investment.builder()
                .userId(USER).type(InvestmentType.BOND).name("REC Bond")
                .purchaseDate(LocalDate.now().minusMonths(30))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(100_000))
                .currentPrice(BigDecimal.valueOf(120_000))
                .unrealizedGainLoss(BigDecimal.valueOf(20_000))
                .build();
        Investment commodity = Investment.builder()
                .userId(USER).type(InvestmentType.COMMODITY).name("Silver ETF")
                .purchaseDate(LocalDate.now().minusMonths(30))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .currentPrice(BigDecimal.valueOf(60_000))
                .unrealizedGainLoss(BigDecimal.valueOf(10_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(bond, commodity));

        assertEquals(30_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(3_750.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "30,000 × 12.5%");
    }

    @Test
    void sovereignGoldBond_nameMatch_isExemptNotFlatRate() {
        Investment sgb = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Sovereign Gold Bond 2031 Series IV")
                .purchaseDate(LocalDate.now().minusYears(3))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .currentValue(BigDecimal.valueOf(70_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(sgb));

        assertEquals(70_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertEquals(0.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("GOLD_ASSUMED_SGB_EXEMPT")));
    }

    @Test
    void physicalGold_nameDoesNotMatchSgb_usesFlatRateRegime() {
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold Coin")
                .purchaseDate(LocalDate.now().minusMonths(25))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(100_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(0.0, est.exemptAmount().doubleValue(), 1e-9);
    }

    // ── Realized netting ─────────────────────────────────────────────────────

    @Test
    void shortTermLossOffsetsLongTermGain_beforeExemption() {
        // STCL −50,000 + LTCG +200,000 → net LTCG 150,000 → taxable 25,000 @12.5% = 3,125
        mockRealized(
                realized("A", -50_000, false, "2025-05-01"),
                realized("B", 200_000, true, "2025-06-01"));

        CapitalGainsTaxService.RealizedTaxSummary s =
                service.realizedTax(USER, LocalDate.parse("2025-06-15"));

        assertEquals(0.0, s.taxableStcg().doubleValue(), 1e-9);
        assertEquals(25_000.0, s.taxableLtcg().doubleValue(), 1e-9);
        assertEquals(3_125.0, s.ltcgTax().doubleValue(), 1e-9);
        assertEquals(0.0, s.exemptionHeadroom().doubleValue(), 1e-9);
    }

    @Test
    void longTermLossDoesNotOffsetShortTermGain() {
        // LTCL −30,000 cannot touch STCG +40,000: STCG taxed in full, LTCL carried forward
        mockRealized(
                realized("A", 40_000, false, "2025-05-01"),
                realized("B", -30_000, true, "2025-06-01"));

        CapitalGainsTaxService.RealizedTaxSummary s =
                service.realizedTax(USER, LocalDate.parse("2025-06-15"));

        assertEquals(40_000.0, s.taxableStcg().doubleValue(), 1e-9);
        assertEquals(8_000.0, s.stcgTax().doubleValue(), 1e-9);
        assertEquals(30_000.0, s.carryForwardLtcl().doubleValue(), 1e-9);
        assertTrue(s.notes().stream().anyMatch(n -> n.startsWith("CARRY_FORWARD")));
    }

    @Test
    void exemptionHeadroom_reflectsLtcgRealizedThisFyOnly() {
        // 50,000 LTCG this FY + a sale outside the FY that must be ignored
        mockRealized(
                realized("A", 50_000, true, "2025-05-01"),
                realized("B", 999_999, true, "2025-03-01"));   // previous FY

        CapitalGainsTaxService.RealizedTaxSummary s =
                service.realizedTax(USER, LocalDate.parse("2025-06-15"));

        assertEquals(LocalDate.parse("2025-04-01"), s.fyStart());
        assertEquals(50_000.0, s.exemptionUsed().doubleValue(), 1e-9);
        assertEquals(75_000.0, s.exemptionHeadroom().doubleValue(), 1e-9);
        assertEquals(0.0, s.taxableLtcg().doubleValue(), 1e-9);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static Investment legacy(String symbol, double cost, double current, double qty) {
        return Investment.builder()
                .userId(USER).type(InvestmentType.STOCK).symbol(symbol).name(symbol)
                .purchaseDate(LocalDate.parse("2016-03-15"))
                .quantity(BigDecimal.valueOf(qty))
                .costPerUnit(BigDecimal.valueOf(cost))
                .currentPrice(BigDecimal.valueOf(current))
                .unrealizedGainLoss(BigDecimal.valueOf((current - cost) * qty))
                .build();
    }

    private void mockRealized(LotTrackingService.RealizedGain... gains) {
        when(lotTrackingService.buildLedger(USER)).thenReturn(
                new LotTrackingService.LotLedger(Map.of(), List.of(gains), List.of()));
    }

    /** Builds a RealizedGain whose gain() equals the requested rupee amount. */
    private static LotTrackingService.RealizedGain realized(String sym, double gain,
                                                            boolean longTerm, String sellDate) {
        // qty 1, cost 0, sell price = gain → gain() = gain
        return new LotTrackingService.RealizedGain(sym,
                LocalDate.parse(sellDate).minusYears(longTerm ? 2 : 0).minusDays(longTerm ? 0 : 30),
                LocalDate.parse(sellDate),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(gain), longTerm);
    }
}
