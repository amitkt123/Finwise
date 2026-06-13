package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes the RBI "current rates" page for the four policy rates that matter to
 * the macro regime — repo, SDF, MSF, CRR (DF-3). These change only at MPC
 * meetings, so a daily pull is cheap insurance: the latest stored value simply
 * persists until RBI moves.
 *
 * <p>Parse-or-skip per rate: a label that isn't found, or a value outside its
 * sanity band, is dropped (the series keeps its prior observation). The whole
 * fetch failing leaves every rate untouched — consumers read the last good
 * value from {@code macro_series}.
 *
 * <p>This supersedes the old config-cross-check {@code RbiRateProvider} as the
 * primary repo-rate source; the levels now land in {@code macro_series} rather
 * than only nagging the user to edit a property.
 */
@Slf4j
@Service
public class RbiPolicyRateProvider {

    private static final String SOURCE = "RBI";

    private final MacroSourceClient client;
    private final String ratesUrl;

    public RbiPolicyRateProvider(
            MacroSourceClient client,
            @Value("${cfo.macro.rbi.rates-url:https://www.rbi.org.in}") String ratesUrl) {
        this.client = client;
        this.ratesUrl = ratesUrl;
    }

    public List<MacroObservation> fetch(LocalDate obsDate) {
        List<MacroObservation> out = new ArrayList<>();
        try {
            Optional<String> html = client.fetch(ratesUrl);
            if (html.isEmpty()) {
                log.warn("[RBI] rates page returned no body");
                return out;
            }
            MacroSeriesParsers.RbiRates rates = MacroSeriesParsers.parseRbiRates(html.get());
            if (rates.isEmpty()) {
                log.warn("[RBI] no policy rates parsed — page structure may have changed");
                return out;
            }
            add(out, MacroSeriesCode.REPO_RATE, rates.repo(), obsDate);
            add(out, MacroSeriesCode.SDF_RATE, rates.sdf(), obsDate);
            add(out, MacroSeriesCode.MSF_RATE, rates.msf(), obsDate);
            add(out, MacroSeriesCode.CRR, rates.crr(), obsDate);
        } catch (RuntimeException e) {
            log.warn("[RBI] policy-rate fetch failed: {}", e.getMessage());
        }
        return out;
    }

    private void add(List<MacroObservation> out, MacroSeriesCode code, BigDecimal value, LocalDate obsDate) {
        if (value == null) return;
        if (!code.isSane(value.doubleValue())) {
            log.warn("[RBI] {} {} outside sanity band, discarding", code, value);
            return;
        }
        out.add(MacroObservation.of(code, obsDate, value, SOURCE));
    }
}
