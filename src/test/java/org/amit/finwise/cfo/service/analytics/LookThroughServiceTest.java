package org.amit.finwise.cfo.service.analytics;

import org.amit.finwise.cfo.model.LookThroughResult;
import org.amit.finwise.cfo.model.MfPortfolioHolding;
import org.amit.finwise.cfo.repository.MfPortfolioHoldingRepository;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService.SymbolEntry;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Look-through tests (Phase 12a): effective exposure = direct + Σ_f w_f·h_{f,stock},
 * unmapped residue tracking, and the 70% MF-coverage gate that governs feedsModel.
 */
@ExtendWith(MockitoExtension.class)
class LookThroughServiceTest {

    @Mock InvestmentRepository investmentRepository;
    @Mock MfPortfolioHoldingRepository mfHoldingRepo;
    @Mock SymbolExtractorService symbolExtractorService;

    private LookThroughService service;
    private static final String USER = "u";

    @BeforeEach
    void setUp() {
        service = new LookThroughService(investmentRepository, mfHoldingRepo, symbolExtractorService);
        lenient().when(symbolExtractorService.getSymbolEntry("HDFCBANK"))
                .thenReturn(new SymbolEntry("HDFCBANK", null, "Banking", List.of()));
        lenient().when(symbolExtractorService.getSymbolEntry("TCS"))
                .thenReturn(new SymbolEntry("TCS", null, "IT", List.of()));
        lenient().when(symbolExtractorService.getSymbolEntry("INFY"))
                .thenReturn(new SymbolEntry("INFY", null, "IT", List.of()));
    }

    private Investment stock(String sym, double value) {
        return Investment.builder().type(InvestmentType.STOCK).symbol(sym)
                .name(sym).currentValue(BigDecimal.valueOf(value)).build();
    }

    private Investment fund(String scheme, double value) {
        return Investment.builder().type(InvestmentType.MUTUAL_FUND).symbol(scheme)
                .name("Fund " + scheme).currentValue(BigDecimal.valueOf(value)).build();
    }

    private MfPortfolioHolding constituent(String scheme, LocalDate asOf, String sym, double pct) {
        return MfPortfolioHolding.builder().schemeCode(scheme).asOf(asOf)
                .symbol(sym).weightPct(pct).build();
    }

    @Test
    void fullCoverageFoldsFundConstituentsIntoEffectiveWeights() {
        LocalDate asOf = LocalDate.now().minusDays(10);
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(stock("HDFCBANK", 60), fund("INF001", 40)));
        when(mfHoldingRepo.latestAsOf("INF001")).thenReturn(asOf);
        when(mfHoldingRepo.findBySchemeCodeAndAsOf("INF001", asOf)).thenReturn(List.of(
                constituent("INF001", asOf, "TCS", 50.0),
                constituent("INF001", asOf, "HDFCBANK", 30.0),
                constituent("INF001", asOf, "INFY", 20.0)));

        LookThroughResult r = service.compute(USER).orElseThrow();

        // effective = direct + w_f·h. HDFCBANK: 0.60 + 0.40·0.30 = 0.72
        assertEquals(0.72, r.effectiveWeights().get("HDFCBANK"), 1e-9);
        assertEquals(0.20, r.effectiveWeights().get("TCS"), 1e-9);     // 0.40·0.50
        assertEquals(0.08, r.effectiveWeights().get("INFY"), 1e-9);    // 0.40·0.20

        assertEquals(0.40, r.mfWeight(), 1e-9);
        assertEquals(0.0, r.unmappedResidue(), 1e-9);
        assertEquals(1.0, r.mfCoverage(), 1e-9);
        assertTrue(r.feedsModel());

        assertEquals(0.72, r.sectorEffective().get("Banking"), 1e-9);
        assertEquals(0.28, r.sectorEffective().get("IT"), 1e-9);

        // Look-through reveals concentration the direct book hides.
        assertTrue(r.effectiveNameHHI() > r.directNameHHI());
    }

    @Test
    void partialDisclosureCarriesResidueAndSuppressesModelFeed() {
        LocalDate asOf = LocalDate.now().minusDays(10);
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(stock("HDFCBANK", 60), fund("INF001", 40)));
        when(mfHoldingRepo.latestAsOf("INF001")).thenReturn(asOf);
        // Only 50% of the fund disclosed.
        when(mfHoldingRepo.findBySchemeCodeAndAsOf("INF001", asOf)).thenReturn(List.of(
                constituent("INF001", asOf, "TCS", 50.0)));

        LookThroughResult r = service.compute(USER).orElseThrow();

        assertEquals(0.20, r.unmappedResidue(), 1e-9);   // 0.40·(1 − 0.50)
        assertEquals(0.50, r.mfCoverage(), 1e-9);
        assertFalse(r.feedsModel(), "coverage 50% < 70% gate");
        assertTrue(r.dataQualityNotes().stream().anyMatch(n -> n.contains("NOTE_ONLY")));
    }

    @Test
    void fundWithoutDisclosureIsAllResidue() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(stock("HDFCBANK", 50), fund("INF999", 50)));
        when(mfHoldingRepo.latestAsOf("INF999")).thenReturn(null);

        LookThroughResult r = service.compute(USER).orElseThrow();

        assertEquals(0.50, r.unmappedResidue(), 1e-9);
        assertEquals(0.0, r.mfCoverage(), 1e-9);
        assertFalse(r.feedsModel());
        assertEquals(0.50, r.effectiveWeights().get("HDFCBANK"), 1e-9);
    }

    @Test
    void noFundsMeansFullCoverageAndModelFeed() {
        when(investmentRepository.findActiveInvestments(USER))
                .thenReturn(List.of(stock("HDFCBANK", 50), stock("TCS", 50)));

        LookThroughResult r = service.compute(USER).orElseThrow();
        assertEquals(0.0, r.mfWeight(), 1e-9);
        assertEquals(1.0, r.mfCoverage(), 1e-9);
        assertTrue(r.feedsModel());
        assertEquals(Optional.empty(),
                Optional.ofNullable(r.fundContributed().get("HDFCBANK")));
    }
}
