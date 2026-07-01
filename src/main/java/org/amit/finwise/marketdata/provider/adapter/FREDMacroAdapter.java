package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FREDMacroAdapter implements MarketFeedProvider {

    private final RestClient.Builder restClientBuilder;

    @Value("${market.fred.api-key:}")
    private String apiKey;

    private static final String BASE = "https://api.stlouisfed.org/fred/series/observations";

    @Override public String name() { return "fred"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.MACRO_GLOBAL; }
    @Override public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }

    public DataEnvelope<GlobalMacroSnapshot> fetchGlobalMacro() {
        try {
            FredObservation fed = fetchSeries("FEDFUNDS");
            FredObservation dxy = fetchSeries("DTWEXBGS");
            FredObservation crude = fetchSeries("DCOILWTICO");
            FredObservation gold = fetchSeries("GOLDAMGBD228NLBM");
            FredObservation vix = fetchSeries("VIXCLS");
            FredObservation us10y = fetchSeries("DGS10");
            String dataDate = List.of(fed, dxy, crude, gold, vix, us10y).stream()
                .filter(o -> o != null && o.date() != null)
                .map(FredObservation::date)
                .findFirst()
                .orElse("unknown");
            GlobalMacroSnapshot snap = new GlobalMacroSnapshot(
                value(fed), value(dxy), value(crude), value(gold), value(vix), value(us10y), dataDate);
            return DataEnvelope.of(snap, name(), DataQuality.EOD);
        } catch (Exception e) {
            log.error("[FRED] fetch failed: {}", e.getMessage());
            return DataEnvelope.missing(name(), "FRED API error: " + e.getMessage());
        }
    }

    private static BigDecimal value(FredObservation obs) {
        return obs == null ? null : obs.value();
    }

    private record FredObservation(String date, BigDecimal value) {}

    private FredObservation fetchSeries(String seriesId) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(uriBuilder -> uriBuilder
                .scheme("https").host("api.stlouisfed.org")
                .path("/fred/series/observations")
                .queryParam("series_id", seriesId)
                .queryParam("api_key", apiKey)
                .queryParam("file_type", "json")
                .queryParam("sort_order", "desc")
                .queryParam("limit", "1")
                .build())
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> observations = (List<Map<String, String>>) response.get("observations");
        if (observations == null || observations.isEmpty()) return null;
        String date = observations.get(0).get("date");
        String value = observations.get(0).get("value");
        BigDecimal parsedValue = ".".equals(value) ? null : new BigDecimal(value);
        return new FredObservation(date, parsedValue);
    }
}
