package org.amit.finwise.marketdata.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Client for the cookie-gated www.nseindia.com JSON APIs (corporate actions,
 * event calendar). Unlike the static archive hosts that {@link NseArchiveClient}
 * uses, these endpoints reject requests without a valid cookie set — so we warm
 * up by GETting a real content page (the bare homepage 403s; an inner listing
 * page returns 200 and the Set-Cookie jar) and replay those cookies, with a
 * browser User-Agent and Referer, on the API calls. A 401/403 transparently
 * re-warms once.
 *
 * Endpoint paths are camelCase as of the 2024 NSE site rebuild
 * (corporates-corporateActions); re-verify if a job starts returning 404.
 */
@Slf4j
@Component
public class NseApiClient {

    private static final String BASE = "https://www.nseindia.com";
    /** Inner page that returns 200 + cookies (the bare homepage 403s for bots). */
    private static final String WARMUP_URL = BASE + "/companies-listing/corporate-filings-actions";
    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final RestClient restClient;

    /** name -> value cookie jar from the last warm-up; replayed on every API call. */
    private final Map<String, String> cookieJar = new LinkedHashMap<>();
    private volatile boolean warmedUp = false;

    public NseApiClient() {
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, UA)
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
                .build();
    }

    /**
     * Corporate-actions JSON for an [from, to] date range (equities). Empty when
     * NSE returns no body. Throws {@link NseArchiveClient.NseArchiveException}
     * on transport failure so the caller can ledger it FAILED.
     */
    public Optional<String> fetchCorporateActions(LocalDate from, LocalDate to) {
        String url = BASE + "/api/corporates-corporateActions?index=equities"
                + "&from_date=" + DD_MM_YYYY.format(from)
                + "&to_date=" + DD_MM_YYYY.format(to);
        return getJson(url, "/companies-listing/corporate-filings-actions");
    }

    /** Forward-looking NSE event calendar (board meetings with stated purpose). */
    public Optional<String> fetchEventCalendar() {
        return getJson(BASE + "/api/event-calendar",
                "/companies-listing/corporate-filings-event-calendar");
    }

    /** Corporate-announcements JSON for an [from, to] date range (equities). */
    public Optional<String> fetchAnnouncements(LocalDate from, LocalDate to) {
        String url = BASE + "/api/corporate-announcements?index=equities"
                + "&from_date=" + DD_MM_YYYY.format(from)
                + "&to_date=" + DD_MM_YYYY.format(to);
        return getJson(url, "/companies-listing/corporate-filings-announcements");
    }

    /** Shareholding-pattern JSON for an [from, to] filing-date range (equities). */
    public Optional<String> fetchShareholdingPatterns(LocalDate from, LocalDate to) {
        String url = BASE + "/api/corporate-share-holdings-master?index=equities"
                + "&from_date=" + DD_MM_YYYY.format(from)
                + "&to_date=" + DD_MM_YYYY.format(to);
        return getJson(url, "/companies-listing/corporate-filings-shareholding-pattern");
    }

    /** Bulk-deals JSON for an [from, to] date range. */
    public Optional<String> fetchBulkDeals(LocalDate from, LocalDate to) {
        String url = BASE + "/api/historical/bulk-deals"
                + "?from=" + DD_MM_YYYY.format(from)
                + "&to=" + DD_MM_YYYY.format(to);
        return getJson(url, "/report-detail/display-bulk-and-block-deals");
    }

    /** Block-deals JSON for an [from, to] date range. */
    public Optional<String> fetchBlockDeals(LocalDate from, LocalDate to) {
        String url = BASE + "/api/historical/block-deals"
                + "?from=" + DD_MM_YYYY.format(from)
                + "&to=" + DD_MM_YYYY.format(to);
        return getJson(url, "/report-detail/display-bulk-and-block-deals");
    }

    private synchronized Optional<String> getJson(String url, String refererPath) {
        if (!warmedUp) warmUp();
        try {
            return doGet(url, refererPath);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 401 || code == 403) {
                log.info("[NseApi] {} on {} — re-warming cookies and retrying", code, url);
                warmedUp = false;
                warmUp();
                return doGet(url, refererPath);
            }
            if (code == 404) return Optional.empty();
            throw new NseArchiveClient.NseArchiveException("HTTP " + code + " from " + url, e);
        } catch (RuntimeException e) {
            throw new NseArchiveClient.NseArchiveException(
                    "Request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    private Optional<String> doGet(String url, String refererPath) {
        String body = restClient.get().uri(url)
                .header(HttpHeaders.ACCEPT, "application/json, text/plain, */*")
                .header("Referer", BASE + refererPath)
                .header(HttpHeaders.COOKIE, cookieHeader())
                .retrieve()
                .body(String.class);
        if (body == null || body.isBlank() || body.startsWith("<")) return Optional.empty();
        return Optional.of(body);
    }

    /** GET the warm-up page and harvest its Set-Cookie jar. */
    private void warmUp() {
        try {
            ResponseEntity<String> resp = restClient.get().uri(WARMUP_URL)
                    .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .retrieve()
                    .toEntity(String.class);
            harvestCookies(resp.getHeaders());
            warmedUp = true;
            log.info("[NseApi] Warm-up OK, {} cookies", cookieJar.size());
        } catch (RestClientResponseException e) {
            // Some Akamai responses 4xx but still set cookies — keep what we got.
            harvestCookies(e.getResponseHeaders());
            warmedUp = !cookieJar.isEmpty();
            log.warn("[NseApi] Warm-up HTTP {} ({} cookies harvested)",
                    e.getStatusCode().value(), cookieJar.size());
        } catch (RuntimeException e) {
            throw new NseArchiveClient.NseArchiveException(
                    "NSE cookie warm-up failed: " + e.getMessage(), e);
        }
    }

    private void harvestCookies(HttpHeaders headers) {
        if (headers == null) return;
        List<String> setCookies = headers.get(HttpHeaders.SET_COOKIE);
        if (setCookies == null) return;
        for (String sc : setCookies) {
            String pair = sc.split(";", 2)[0];
            int eq = pair.indexOf('=');
            if (eq > 0) cookieJar.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
    }

    private String cookieHeader() {
        List<String> parts = new ArrayList<>();
        cookieJar.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join("; ", parts);
    }
}
