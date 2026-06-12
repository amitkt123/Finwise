package org.amit.finwise.cfo.service.ingestion;

import org.amit.finwise.cfo.model.MfPortfolioHolding;
import org.amit.finwise.cfo.repository.MfPortfolioHoldingRepository;
import org.amit.finwise.cfo.service.ingestion.MfPortfolioImportService.ImportSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Parsing/persistence semantics for the manual MF portfolio CSV import (Phase 12a). */
@ExtendWith(MockitoExtension.class)
class MfPortfolioImportServiceTest {

    @Mock MfPortfolioHoldingRepository repo;

    @SuppressWarnings("unchecked")
    @Test
    void parsesHeaderedCsvReplacesSnapshotAndSumsWeights() {
        MfPortfolioImportService svc = new MfPortfolioImportService(repo);
        String csv = """
                schemeCode,asOf,isin,symbol,weightPct,sector
                INF001,2026-04-30,INE467B01029,TCS,12.5,IT
                INF001,2026-04-30,INE040A01034,HDFCBANK,9.0,Banking
                # a comment line is ignored
                INF001,2026-04-30,,INFY,8.5,IT
                """;

        ImportSummary summary = svc.importCsv(csv);

        assertEquals("INF001", summary.schemeCode());
        assertEquals(LocalDate.of(2026, 4, 30), summary.asOf());
        assertEquals(3, summary.rowsImported());
        assertEquals(30.0, summary.totalWeightPct(), 1e-9);

        // Prior snapshot for this scheme/date is cleared before insert (idempotent re-import).
        verify(repo).deleteBySchemeCodeAndAsOf("INF001", LocalDate.of(2026, 4, 30));

        ArgumentCaptor<List<MfPortfolioHolding>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        assertEquals(3, captor.getValue().size());
        assertEquals("TCS", captor.getValue().get(0).getSymbol());
    }

    @Test
    void rejectsCsvWithNoValidRows() {
        MfPortfolioImportService svc = new MfPortfolioImportService(repo);
        assertThrows(IllegalArgumentException.class,
                () -> svc.importCsv("# only comments\n\n"));
        verify(repo, never()).saveAll(any());
    }

    @Test
    void skipsMalformedRowsButKeepsValidOnes() {
        MfPortfolioImportService svc = new MfPortfolioImportService(repo);
        String csv = """
                INF002,2026-03-31,,TCS,15.0,IT
                INF002,not-a-date,,INFY,10.0,IT
                INF002,2026-03-31,,RELIANCE,abc,Energy
                """;
        ImportSummary summary = svc.importCsv(csv);
        assertEquals(1, summary.rowsImported());
        assertTrue(summary.warnings().size() >= 2);
    }
}
