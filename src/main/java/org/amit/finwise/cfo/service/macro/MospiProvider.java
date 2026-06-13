package org.amit.finwise.cfo.service.macro;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Monthly MOSPI prints — CPI (headline + core), IIP, WPI — via the free
 * data.gov.in resource API (DF-3). Each resource returns its full history in one
 * call, so a single fetch both seeds 3y+ and refreshes the latest month;
 * upserts make re-runs idempotent.
 *
 * <p>Disabled gracefully when no API key is set (returns empty). Resource IDs and
 * the month/year/value field names are config-driven because they differ per
 * dataset and data.gov.in revises them periodically.
 */
@Slf4j
@Service
public class MospiProvider {

    private static final String SOURCE = "MOSPI";

    private final MacroSourceClient client;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final int limit;
    private final String monthField;
    private final String yearField;
    private final String valueField;
    private final Map<MacroSeriesCode, String> resourceIds;

    public MospiProvider(
            MacroSourceClient client,
            ObjectMapper objectMapper,
            @Value("${cfo.macro.mospi.base-url:https://api.data.gov.in/resource}") String baseUrl,
            @Value("${cfo.macro.mospi.api-key:}") String apiKey,
            @Value("${cfo.macro.mospi.limit:1000}") int limit,
            @Value("${cfo.macro.mospi.month-field:month}") String monthField,
            @Value("${cfo.macro.mospi.year-field:year}") String yearField,
            @Value("${cfo.macro.mospi.value-field:value}") String valueField,
            @Value("${cfo.macro.mospi.cpi-headline-resource:}") String cpiHeadlineResource,
            @Value("${cfo.macro.mospi.cpi-core-resource:}") String cpiCoreResource,
            @Value("${cfo.macro.mospi.iip-resource:}") String iipResource,
            @Value("${cfo.macro.mospi.wpi-resource:}") String wpiResource) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.limit = limit;
        this.monthField = monthField;
        this.yearField = yearField;
        this.valueField = valueField;
        this.resourceIds = new EnumMap<>(MacroSeriesCode.class);
        putIfPresent(MacroSeriesCode.CPI_HEADLINE, cpiHeadlineResource);
        putIfPresent(MacroSeriesCode.CPI_CORE, cpiCoreResource);
        putIfPresent(MacroSeriesCode.IIP, iipResource);
        putIfPresent(MacroSeriesCode.WPI, wpiResource);
    }

    private void putIfPresent(MacroSeriesCode code, String resourceId) {
        if (resourceId != null && !resourceId.isBlank()) resourceIds.put(code, resourceId.trim());
    }

    /**
     * Pulls every configured MOSPI series' full history. Each monthly record
     * becomes an observation dated to its month-end. Sanity-banded; out-of-band
     * months are dropped.
     */
    public List<MacroObservation> fetch() {
        List<MacroObservation> out = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[MOSPI] data.gov.in API key not set; skipping CPI/IIP/WPI");
            return out;
        }
        resourceIds.forEach((code, resourceId) -> out.addAll(fetchSeries(code, resourceId)));
        return out;
    }

    private List<MacroObservation> fetchSeries(MacroSeriesCode code, String resourceId) {
        List<MacroObservation> out = new ArrayList<>();
        String url = String.format("%s/%s?api-key=%s&format=json&limit=%d",
                baseUrl, resourceId, URLEncoder.encode(apiKey, StandardCharsets.UTF_8), limit);
        try {
            Optional<String> json = client.fetch(url);
            if (json.isEmpty()) {
                log.warn("[MOSPI] {} resource returned no body", code);
                return out;
            }
            List<MacroSeriesParsers.MospiObs> obs = MacroSeriesParsers.parseDataGovRecords(
                    json.get(), monthField, yearField, valueField, objectMapper);
            for (MacroSeriesParsers.MospiObs o : obs) {
                if (code.isSane(o.value().doubleValue())) {
                    out.add(MacroObservation.of(code, o.obsDate(), o.value(), SOURCE));
                }
            }
            log.info("[MOSPI] {}: {} observations parsed", code, out.size());
        } catch (RuntimeException e) {
            log.warn("[MOSPI] {} fetch failed: {}", code, e.getMessage());
        }
        return out;
    }
}
