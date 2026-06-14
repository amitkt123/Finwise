package org.amit.finwise.cfo.service.rag;

import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.NewsCluster;
import org.amit.finwise.cfo.repository.*;
import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Outcome-enrichment math (DF-6): excess return vs Nifty, trading-day horizons,
 * and idempotency.
 */
class EventOutcomeServiceTest {

    private final NewsClusterRepository clusterRepo = mock(NewsClusterRepository.class);
    private final NewsArticleRepository articleRepo = mock(NewsArticleRepository.class);
    private final ArticleEntityRepository entityRepo = mock(ArticleEntityRepository.class);
    private final MarketEventRepository eventRepo = mock(MarketEventRepository.class);
    private final EventOutcomeRepository outcomeRepo = mock(EventOutcomeRepository.class);
    private final InstrumentRepository instrumentRepo = mock(InstrumentRepository.class);
    private final EodPriceRepository eodRepo = mock(EodPriceRepository.class);
    private final IndexEodRepository indexRepo = mock(IndexEodRepository.class);
    private final IngestionRunRepository runRepo = mock(IngestionRunRepository.class);

    private EventOutcomeService service;

    @BeforeEach
    void setUp() {
        service = new EventOutcomeService(clusterRepo, articleRepo, entityRepo, eventRepo,
                outcomeRepo, instrumentRepo, eodRepo, indexRepo, runRepo);
        // benchmark-index @Value is not injected in a plain unit test; set the default.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "benchmarkIndex", "Nifty 50");

        NewsCluster cluster = NewsCluster.builder()
                .id(7L).canonicalArticleId(70L)
                .firstSeen(LocalDateTime.of(2024, 1, 1, 9, 0))
                .build();
        when(clusterRepo.findOpenedBetween(any(), any())).thenReturn(List.of(cluster));
        when(articleRepo.findById(70L)).thenReturn(Optional.of(
                NewsArticle.builder().id(70L).title("TCS Q3 results beat")
                        .category(NewsArticle.Category.EARNINGS)
                        .sentiment(NewsArticle.Sentiment.POSITIVE).build()));
        when(eventRepo.findByClusterId(7L)).thenReturn(Optional.empty());
        when(entityRepo.findInstrumentSymbolsForCluster(7L)).thenReturn(List.of("TCS"));
        when(instrumentRepo.findBySymbol("TCS")).thenReturn(Optional.of(
                Instrument.builder().id(1L).symbol("TCS").isin("INE467B01029").build()));
        when(outcomeRepo.findByClusterIdAndInstrumentIdAndHorizon(anyLong(), anyLong(), any()))
                .thenReturn(Optional.empty());
        // Flat benchmark: +2% over any window.
        when(indexRepo.findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(
                anyString(), any(), any()))
                .thenReturn(List.of(index(100), index(102)));
    }

    @Test
    void computesExcessReturnOverBenchmarkForEachHorizon() {
        // close = 100 + dayIndex, so day0=100, day1=101, day5=105, day20=120.
        when(eodRepo.findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(eq(1L), any(), any()))
                .thenReturn(priceSeries(21));

        EventOutcomeService.EnrichResult result = service.enrichOutcomes();

        assertEquals(1, result.clustersScanned());
        assertEquals(3, result.outcomesWritten());   // 1d, 5d, 20d all matured

        ArgumentCaptor<EventOutcome> cap = ArgumentCaptor.forClass(EventOutcome.class);
        verify(outcomeRepo, times(3)).save(cap.capture());

        EventOutcome h5 = cap.getAllValues().stream()
                .filter(o -> o.getHorizon() == EventOutcome.Horizon.H5D).findFirst().orElseThrow();
        // raw = (105-100)/100 = 0.05 ; bench = 0.02 ; excess = 0.03
        assertEquals(0.05, h5.getRawReturn(), 1e-9);
        assertEquals(0.03, h5.getExcessReturnVsNifty(), 1e-9);
        assertEquals("TCS", h5.getSymbol());
    }

    @Test
    void skipsHorizonsThatHaveNotMaturedYet() {
        // Only 3 trading days of data: 1d matured, 5d and 20d not.
        when(eodRepo.findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(eq(1L), any(), any()))
                .thenReturn(priceSeries(3));

        EventOutcomeService.EnrichResult result = service.enrichOutcomes();

        assertEquals(1, result.outcomesWritten());
        verify(outcomeRepo, times(1)).save(any());
    }

    @Test
    void isIdempotent_skipsAlreadyComputedOutcomes() {
        when(eodRepo.findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(eq(1L), any(), any()))
                .thenReturn(priceSeries(21));
        when(outcomeRepo.findByClusterIdAndInstrumentIdAndHorizon(anyLong(), anyLong(), any()))
                .thenReturn(Optional.of(new EventOutcome()));   // all horizons already present

        EventOutcomeService.EnrichResult result = service.enrichOutcomes();

        assertEquals(0, result.outcomesWritten());
        verify(outcomeRepo, never()).save(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<EodPrice> priceSeries(int n) {
        List<EodPrice> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(EodPrice.builder()
                    .instrumentId(1L)
                    .tradeDate(LocalDate.of(2024, 1, 1).plusDays(i))
                    .close(BigDecimal.valueOf(100 + i))
                    .build());
        }
        return rows;
    }

    private IndexEod index(int close) {
        return IndexEod.builder().indexName("Nifty 50").close(BigDecimal.valueOf(close)).build();
    }
}
