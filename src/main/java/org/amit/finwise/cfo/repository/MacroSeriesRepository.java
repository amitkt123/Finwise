package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.MacroSeries;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface MacroSeriesRepository extends JpaRepository<MacroSeries, Long> {

    /** Most recent observation for a series, or empty if never ingested. */
    Optional<MacroSeries> findTopBySeriesCodeOrderByObsDateDesc(MacroSeriesCode code);

    /** Most recent observation on or before a date — the as-of value for a series. */
    Optional<MacroSeries> findTopBySeriesCodeAndObsDateLessThanEqualOrderByObsDateDesc(
            MacroSeriesCode code, LocalDate asOf);

    /** The exact observation for one (code, date), used for idempotent upserts. */
    Optional<MacroSeries> findBySeriesCodeAndObsDate(MacroSeriesCode code, LocalDate obsDate);

    /** Full history of a series, oldest first. */
    List<MacroSeries> findBySeriesCodeOrderByObsDate(MacroSeriesCode code);

    long countBySeriesCode(MacroSeriesCode code);
}
