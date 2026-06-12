package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.repository.StockFundamentalsRepository;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cross-sectional peer percentile tests with hand-computed Hazen ranks.
 */
@ExtendWith(MockitoExtension.class)
class PeerUniverseServiceTest {

    @Mock SymbolExtractorService symbolExtractorService;
    @Mock StockFundamentalsRepository fundamentalsRepo;
    @Mock FundamentalsService fundamentalsService;
    @Mock InvestmentRepository investmentRepository;

    private PeerUniverseService service;

    @BeforeEach
    void setUp() {
        service = new PeerUniverseService(symbolExtractorService, fundamentalsRepo,
                fundamentalsService, investmentRepository, 0);
        lenient().when(fundamentalsRepo.save(any(StockFundamentals.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void percentileRanksTargetAgainstSectorPeers() {
        // Peers A..E with P/E 10,20,30,40,50; target TGT at P/E 25.
        // Sample of 6 → TGT above 2 of 6 → Hazen (3 − 0.5)/6 ≈ 41.7%
        when(symbolExtractorService.symbolsInSector("IT"))
                .thenReturn(List.of("A", "B", "C", "D", "E", "TGT"));
        mockPeer("A", 10);
        mockPeer("B", 20);
        mockPeer("C", 30);
        mockPeer("D", 40);
        mockPeer("E", 50);
        when(fundamentalsRepo.findTopBySymbolOrderBySnapshotDateDesc("TGT"))
                .thenReturn(Optional.empty());

        StockFundamentals target = fundamentals("TGT", 25);
        StockFundamentals updated = service.applyPercentiles(target, "IT");

        assertNotNull(updated.getPeerPePercentile());
        assertEquals(41.7, updated.getPeerPePercentile().doubleValue(), 0.05);
        assertTrue(updated.getPeerGroup().contains("IT"), "peer group must name the sector");
    }

    @Test
    void lossMakingPeersAreExcludedFromPeRankingAndDisclosed() {
        when(symbolExtractorService.symbolsInSector("Metal"))
                .thenReturn(List.of("A", "B", "C", "D", "E", "F", "TGT"));
        mockPeer("A", 5);
        mockPeer("B", 8);
        mockPeer("C", 12);
        mockPeer("D", 15);
        mockPeer("E", 20);
        mockPeer("F", -3);   // loss-making: negative P/E is not "cheap"
        when(fundamentalsRepo.findTopBySymbolOrderBySnapshotDateDesc("TGT"))
                .thenReturn(Optional.empty());

        StockFundamentals updated = service.applyPercentiles(fundamentals("TGT", 10), "Metal");

        // Positive sample {5,8,12,15,20,10}: 10 above 2 of 6 → (3 − 0.5)/6 ≈ 41.7%
        assertEquals(41.7, updated.getPeerPePercentile().doubleValue(), 0.05);
        assertTrue(updated.getPeerGroup().contains("loss-making"),
                "exclusion must be disclosed, got " + updated.getPeerGroup());
    }

    @Test
    void thinUniverseLeavesFieldsNull() {
        when(symbolExtractorService.symbolsInSector("Cement"))
                .thenReturn(List.of("A", "B", "TGT"));

        StockFundamentals updated = service.applyPercentiles(fundamentals("TGT", 25), "Cement");

        assertNull(updated.getPeerPePercentile(), "3 peers is false precision — stay null");
        assertNull(updated.getPeerGroup());
    }

    private void mockPeer(String symbol, double pe) {
        when(fundamentalsRepo.findTopBySymbolOrderBySnapshotDateDesc(symbol))
                .thenReturn(Optional.of(fundamentals(symbol, pe)));
    }

    private static StockFundamentals fundamentals(String symbol, double pe) {
        return StockFundamentals.builder()
                .symbol(symbol)
                .snapshotDate(LocalDate.now())
                .trailingPE(BigDecimal.valueOf(pe))
                .build();
    }
}
