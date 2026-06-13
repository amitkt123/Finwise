package org.amit.finwise.cfo.service.macro;

import org.amit.finwise.cfo.model.MacroSeriesCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One macro datum a provider hands to {@link MacroSeriesService} for upsert into
 * {@code cfo_macro_series}: which series, for which date, the value, and where
 * it came from.
 */
public record MacroObservation(MacroSeriesCode code, LocalDate obsDate, BigDecimal value, String source) {

    public static MacroObservation of(MacroSeriesCode code, LocalDate obsDate, BigDecimal value, String source) {
        return new MacroObservation(code, obsDate, value, source);
    }
}
