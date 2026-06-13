package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * FBIL (Financial Benchmarks India) daily publications (DF-3):
 *   - the G-sec par yield curve → GSEC_3M / 1Y / 5Y / 10Y, and
 *   - the USD/INR reference rate → USD_INR.
 *
 * <p>Both URLs are config-driven (FBIL changes paths without notice) and the
 * fetch is parse-or-skip: any HTTP failure, empty file, or out-of-band value is
 * dropped and logged, never propagated. The slope (10Y−3M) and Sharpe risk-free
 * rate are computed downstream from the stored tenors — this provider only
 * harvests the levels.
 */
@Slf4j
@Service
public class FbilProvider {

    private static final String SOURCE = "FBIL";

    private final MacroSourceClient client;
    private final String parYieldUrl;
    private final String referenceRateUrl;

    public FbilProvider(MacroSourceClient client,
                        @Value("${cfo.macro.fbil.par-yield-url:}") String parYieldUrl,
                        @Value("${cfo.macro.fbil.reference-rate-url:}") String referenceRateUrl) {
        this.client = client;
        this.parYieldUrl = parYieldUrl;
        this.referenceRateUrl = referenceRateUrl;
    }

    /**
     * Today's curve tenors plus the USD/INR reference rate, sanity-banded, dated
     * {@code obsDate}. Returns whatever it could obtain (possibly empty) — the
     * orchestrator tolerates partial results.
     */
    public List<MacroObservation> fetch(LocalDate obsDate) {
        List<MacroObservation> out = new ArrayList<>(fetchParYields(obsDate));
        fetchUsdInr(obsDate).ifPresent(out::add);
        return out;
    }

    private List<MacroObservation> fetchParYields(LocalDate obsDate) {
        List<MacroObservation> out = new ArrayList<>();
        if (parYieldUrl == null || parYieldUrl.isBlank()) {
            log.debug("[FBIL] par-yield URL not configured; skipping");
            return out;
        }
        try {
            Optional<String> csv = client.fetch(parYieldUrl);
            if (csv.isEmpty()) return out;
            Map<MacroSeriesCode, BigDecimal> tenors =
                    MacroSeriesParsers.parseFbilParYields(csv.get());
            tenors.forEach((code, value) -> {
                if (code.isSane(value.doubleValue())) {
                    out.add(MacroObservation.of(code, obsDate, value, SOURCE));
                } else {
                    log.warn("[FBIL] {} {} outside sanity band, discarding", code, value);
                }
            });
            if (out.isEmpty()) log.warn("[FBIL] par-yield file fetched but no usable tenors parsed");
        } catch (RuntimeException e) {
            log.warn("[FBIL] par-yield fetch failed: {}", e.getMessage());
        }
        return out;
    }

    private Optional<MacroObservation> fetchUsdInr(LocalDate obsDate) {
        if (referenceRateUrl == null || referenceRateUrl.isBlank()) {
            log.debug("[FBIL] reference-rate URL not configured; skipping USD/INR");
            return Optional.empty();
        }
        try {
            Optional<String> body = client.fetch(referenceRateUrl);
            if (body.isEmpty()) return Optional.empty();
            Optional<BigDecimal> rate = MacroSeriesParsers.parseUsdInrReferenceRate(body.get());
            if (rate.isEmpty()) {
                log.warn("[FBIL] USD/INR marker not found in reference-rate file");
                return Optional.empty();
            }
            if (!MacroSeriesCode.USD_INR.isSane(rate.get().doubleValue())) {
                log.warn("[FBIL] USD/INR {} outside sanity band, discarding", rate.get());
                return Optional.empty();
            }
            return Optional.of(MacroObservation.of(MacroSeriesCode.USD_INR, obsDate, rate.get(), SOURCE));
        } catch (RuntimeException e) {
            log.warn("[FBIL] USD/INR fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
