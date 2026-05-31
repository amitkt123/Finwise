package org.amit.finwise.cfo.service.research;

import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.service.analytics.CovarianceEngine;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.cfo.service.analytics.TechnicalAnalysisService;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.cfo.service.macro.MacroStateService;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the dangerous failure mode (§6 #1): a research query for a symbol
 * with no price history must surface DATA_INCOMPLETE rather than letting the LLM substitute
 * training-data recall.
 *
 * All collaborators return their Mockito defaults (empty Optional / empty collections),
 * simulating an unknown, un-held ticker with no ingested data.
 */
@ExtendWith(MockitoExtension.class)
class StockIntelligenceServiceGoldenTest {

    @Mock StockPriceHistoryRepository priceRepo;
    @Mock NewsArticleRepository newsRepo;
    @Mock InvestmentRepository investmentRepo;
    @Mock TechnicalAnalysisService technicalAnalysisService;
    @Mock PortfolioRiskService portfolioRiskService;
    @Mock ReturnSeriesService returnSeriesService;
    @Mock CovarianceEngine covarianceEngine;
    @Mock SymbolExtractorService symbolExtractorService;
    @Mock FundamentalsService fundamentalsService;
    @Mock MacroStateService macroStateService;

    @InjectMocks
    StockIntelligenceService service;

    @Test
    void unknownUnheldSymbol_yieldsDataIncompleteAndNoFabricatedData() {
        StockDeepDive deepDive = service.analyze("ZZZZZ", "user-1");

        assertNotNull(deepDive, "analyze must always return a (possibly thin) DeepDive");
        assertFalse(deepDive.hasPriceHistory(), "no price rows → hasPriceHistory must be false");
        assertFalse(deepDive.knownSymbol(), "unknown ticker → knownSymbol must be false");

        assertTrue(
                deepDive.dataGaps().stream()
                        .anyMatch(g -> g.startsWith("DATA_INCOMPLETE:PRICE_HISTORY")),
                "must declare the missing price history as DATA_INCOMPLETE:PRICE_HISTORY");
    }
}
