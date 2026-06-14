package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Simulate-and-recover tests for the multi-factor model: a 200-day return fixture
 * is generated from KNOWN betas with a fixed seed, and the OLS must recover them
 * within ±0.1 (systematic variance share within ±5 pts of the population value).
 * Plus data-quality semantics: 120-obs exclusion, missing SIZE factor, unmapped sector.
 */
@ExtendWith(MockitoExtension.class)
class FactorModelServiceTest {

    @Mock ReturnSeriesService returnSeriesService;
    @Mock InvestmentRepository investmentRepository;
    @Mock SymbolExtractorService symbolExtractorService;

    private FactorModelService service;
    private final FactorProperties props = new FactorProperties();

    private static final String USER = "u";
    private static final int T = 200;

    // Generating model for the fixture (daily):
    //   mkt    = 0.0004 + 0.010·g   →  var 1.00e-4
    //   size   =          0.004·g   →  var 1.60e-5   (midcap = mkt + size)
    //   sector =          0.006·g   →  var 3.60e-5   (^CNXIT = mkt + sector)
    //   stock  = 1.2·mkt + 0.5·size + 0.8·sector + 0.005·g
    // Idio vol 0.005 keeps se(β_mkt) ≈ 0.035, so the ±0.1 recovery band is ~3σ.
    private static final double B_MKT = 1.2, B_SIZE = 0.5, B_SECTOR = 0.8;
    private static final double VOL_MKT = 0.010, VOL_SIZE = 0.004, VOL_SECTOR = 0.006, VOL_IDIO = 0.005;

    @BeforeEach
    void setUp() {
        service = new FactorModelService(
                returnSeriesService,
                new FactorReturnService(returnSeriesService, props),
                new CovarianceEngine(),
                investmentRepository,
                symbolExtractorService,
                props);
    }

    @Test
    void simulateAndRecover_threeFactorBetasWithinTolerance() {
        Map<String, NavigableMap<LocalDate, Double>> master = simulatedMarket(new Random(42), true);
        stubReturnSeries(master);
        when(investmentRepository.findActiveInvestments(USER)).thenReturn(List.of(inv("TCS", 100_000)));
        when(symbolExtractorService.getSymbolEntry("TCS")).thenReturn(entry("TCS", "IT"));

        FactorRiskReport fr = service.compute(USER).orElseThrow();

        assertEquals(List.of("TCS"), fr.includedSymbols());
        FactorRiskReport.HoldingFactorExposure h = fr.holdings().getFirst();
        assertEquals("^CNXIT", h.sectorFactor());
        assertEquals(B_MKT, h.betaMkt(), 0.1, "market beta must recover within ±0.1");
        assertEquals(B_SIZE, h.betaSize(), 0.1, "size beta must recover within ±0.1");
        assertEquals(B_SECTOR, h.betaSector(), 0.1, "sector beta must recover within ±0.1");
        assertTrue(h.rSquared() > 0.5, "factor fit must explain most variance, got R²=" + h.rSquared());
        assertEquals(T, h.observations());

        // Single holding at weight 1 → portfolio exposures equal the holding betas
        assertEquals(h.betaMkt(), fr.portfolioFactorBetas().get("MKT"), 1e-12);
        assertEquals(h.betaSize(), fr.portfolioFactorBetas().get("SIZE"), 1e-12);
        assertEquals(h.betaSector(), fr.portfolioFactorBetas().get("^CNXIT"), 1e-12);

        // Population systematic share:
        //   sys  = 1.2²·1e-4 + 0.5²·1.6e-5 + 0.8²·3.6e-5 = 1.7104e-4
        //   idio = 0.008² = 6.4e-5  →  share = 1.7104 / 2.3504 = 72.77%
        double sysVar = B_MKT * B_MKT * VOL_MKT * VOL_MKT
                + B_SIZE * B_SIZE * VOL_SIZE * VOL_SIZE
                + B_SECTOR * B_SECTOR * VOL_SECTOR * VOL_SECTOR;
        double expectedShare = sysVar / (sysVar + VOL_IDIO * VOL_IDIO) * 100;
        assertEquals(expectedShare, fr.systematicSharePct(), 5.0,
                "systematic share must recover within ±5 pts");

        // Model total and the direct wᵀΣw figure are two estimates of the same vol
        assertFalse(Double.isNaN(fr.directVolAnnualized()));
        assertEquals(fr.directVolAnnualized(), fr.totalVolAnnualized(),
                fr.directVolAnnualized() * 0.15,
                "model total vol and direct vol must agree to ~15% on clean simulated data");

        assertFalse(fr.isLowConfidence());
        assertEquals(0.0, fr.excludedWeightPct(), 1e-12);
    }

    @Test
    void holdingBelowMinObservations_isExcludedWithNote() {
        Map<String, NavigableMap<LocalDate, Double>> master = simulatedMarket(new Random(7), false);
        // BBB has 80 valid days — above ReturnSeriesService's 60-obs floor but
        // below the factor model's 120-obs alignment requirement.
        Random rng = new Random(11);
        master.put("BBB", series(dates(80), _ -> 0.005 * rng.nextGaussian()));
        stubReturnSeries(master);
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(inv("TCS", 70_000), inv("BBB", 30_000)));
        when(symbolExtractorService.getSymbolEntry("TCS")).thenReturn(entry("TCS", "IT"));
        lenient().when(symbolExtractorService.getSymbolEntry("BBB")).thenReturn(null);

        FactorRiskReport fr = service.compute(USER).orElseThrow();

        assertEquals(List.of("TCS"), fr.includedSymbols());
        assertEquals(List.of("BBB"), fr.excludedSymbols());
        assertEquals(0.3, fr.excludedWeightPct(), 1e-12,
                "excluded weight must be the raw pre-normalization share");
        assertTrue(fr.isLowConfidence(), "30% excluded weight must force LOW_CONFIDENCE");
        assertTrue(fr.dataQualityNotes().stream().anyMatch(n -> n.startsWith("FACTOR_EXCLUDED: BBB")),
                "exclusion must be disclosed, got " + fr.dataQualityNotes());
        assertEquals(1.0, fr.holdings().getFirst().weight(), 1e-12, "weights renormalize to included");
    }

    @Test
    void missingMidcapIndex_dropsSizeFactorWithNote() {
        Map<String, NavigableMap<LocalDate, Double>> master = simulatedMarket(new Random(42), true);
        master.remove("^NSEMDCP50");
        stubReturnSeries(master);
        when(investmentRepository.findActiveInvestments(USER)).thenReturn(List.of(inv("TCS", 100_000)));
        when(symbolExtractorService.getSymbolEntry("TCS")).thenReturn(entry("TCS", "IT"));

        FactorRiskReport fr = service.compute(USER).orElseThrow();

        FactorRiskReport.HoldingFactorExposure h = fr.holdings().getFirst();
        assertNull(h.betaSize(), "SIZE must not appear when the midcap index is missing");
        assertFalse(fr.factorNames().contains("SIZE"));
        // SIZE was a real return driver in the fixture; without it the residual
        // absorbs that variance but MKT must still recover (factors are independent).
        assertEquals(B_MKT, h.betaMkt(), 0.1);
        assertTrue(fr.isLowConfidence(), "a missing factor must force LOW_CONFIDENCE");
        assertTrue(fr.dataQualityNotes().stream().anyMatch(n -> n.startsWith("SIZE_FACTOR_UNAVAILABLE")),
                "missing SIZE must be disclosed, got " + fr.dataQualityNotes());
    }

    @Test
    void unmappedSector_regressesOnMarketAndSizeOnly() {
        Map<String, NavigableMap<LocalDate, Double>> master = simulatedMarket(new Random(42), true);
        stubReturnSeries(master);
        when(investmentRepository.findActiveInvestments(USER)).thenReturn(List.of(inv("TCS", 100_000)));
        // Telecom has no entry in the sector→index map
        when(symbolExtractorService.getSymbolEntry("TCS")).thenReturn(entry("TCS", "Telecom"));

        FactorRiskReport fr = service.compute(USER).orElseThrow();

        FactorRiskReport.HoldingFactorExposure h = fr.holdings().getFirst();
        assertNull(h.sectorFactor());
        assertNull(h.betaSector());
        assertNotNull(h.betaSize(), "SIZE stays in the regression for unmapped sectors");
        assertEquals(List.of("MKT", "SIZE"), fr.factorNames());
    }

    // ── Fixture generation ────────────────────────────────────────────────────

    /**
     * Builds the simulated index + stock universe. The stock loads on all three
     * factors when {@code withSectorLoading}; gaussian draws are interleaved per
     * day so removing an index from the map never shifts the other series.
     */
    private static Map<String, NavigableMap<LocalDate, Double>> simulatedMarket(
            Random rng, boolean withSectorLoading) {
        LocalDate[] d = dates(T);
        double[] mkt = new double[T], size = new double[T], sector = new double[T], idio = new double[T];
        for (int t = 0; t < T; t++) {
            mkt[t] = 0.0004 + VOL_MKT * rng.nextGaussian();
            size[t] = VOL_SIZE * rng.nextGaussian();
            sector[t] = VOL_SECTOR * rng.nextGaussian();
            idio[t] = VOL_IDIO * rng.nextGaussian();
        }

        Map<String, NavigableMap<LocalDate, Double>> m = new LinkedHashMap<>();
        m.put("^NSEI", series(d, t -> mkt[t]));
        m.put("^NSEMDCP50", series(d, t -> mkt[t] + size[t]));
        m.put("^CNXIT", series(d, t -> mkt[t] + sector[t]));
        m.put("TCS", series(d, t -> B_MKT * mkt[t] + B_SIZE * size[t]
                + (withSectorLoading ? B_SECTOR * sector[t] : 0.0) + idio[t]));
        return m;
    }

    /** ReturnSeriesService contract: return only the requested symbols that exist. */
    private void stubReturnSeries(Map<String, NavigableMap<LocalDate, Double>> master) {
        when(returnSeriesService.getReturnSeries(anyList(), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    List<String> requested = invocation.getArgument(0);
                    Map<String, NavigableMap<LocalDate, Double>> out = new LinkedHashMap<>();
                    for (String s : requested) {
                        if (master.containsKey(s)) out.put(s, master.get(s));
                    }
                    return out;
                });
    }

    private static Investment inv(String symbol, double currentValue) {
        return Investment.builder()
                .userId(USER)
                .symbol(symbol)
                .name(symbol)
                .currentValue(BigDecimal.valueOf(currentValue))
                .build();
    }

    private static SymbolExtractorService.SymbolEntry entry(String symbol, String sector) {
        return new SymbolExtractorService.SymbolEntry(symbol, null, sector, List.of());
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
