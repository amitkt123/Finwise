package org.amit.finwise.cfo.service.macro;

import org.amit.finwise.cfo.model.MacroSeries;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.amit.finwise.cfo.repository.MacroSeriesRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Idempotency of {@link MacroSeriesService#upsert}: re-ingesting the same
 * (code, obs_date) must update the row in place, never duplicate it — the
 * property the per-phase DF verification calls for.
 */
class MacroSeriesServiceUpsertTest {

    private final List<MacroSeries> store = new ArrayList<>();
    private MacroSeriesService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        MacroSeriesRepository repo = mock(MacroSeriesRepository.class);
        when(repo.findBySeriesCodeOrderByObsDate(any())).thenAnswer(inv -> {
            MacroSeriesCode code = inv.getArgument(0);
            return store.stream().filter(s -> s.getSeriesCode() == code).toList();
        });
        when(repo.saveAll(any())).thenAnswer(inv -> {
            Iterable<MacroSeries> rows = inv.getArgument(0);
            for (MacroSeries r : rows) if (!store.contains(r)) store.add(r);
            return new ArrayList<>(store);
        });
        service = new MacroSeriesService(repo, mock(FbilProvider.class), mock(RbiPolicyRateProvider.class),
                mock(MospiProvider.class), mock(IndexEodRepository.class));
    }

    @Test
    void reIngestingSameDateUpdatesInPlace() {
        LocalDate date = LocalDate.of(2026, 6, 13);
        MacroObservation first = MacroObservation.of(
                MacroSeriesCode.REPO_RATE, date, new BigDecimal("6.50"), "RBI");

        service.upsert(List.of(first));
        service.upsert(List.of(first));

        assertEquals(1, store.size(), "same (code, date) re-ingested must not duplicate");

        MacroObservation revised = MacroObservation.of(
                MacroSeriesCode.REPO_RATE, date, new BigDecimal("6.25"), "RBI");
        MacroSeriesService.IngestResult r = service.upsert(List.of(revised));

        assertEquals(1, store.size());
        assertEquals(new BigDecimal("6.25"), store.getFirst().getValue(), "value updated in place");
        assertEquals(1, r.observations());
        assertEquals(1, r.seriesTouched());
    }
}
