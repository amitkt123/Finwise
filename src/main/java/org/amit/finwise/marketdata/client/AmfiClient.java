package org.amit.finwise.marketdata.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Downloads AMFI's official NAV text files (DF-4). Both endpoints serve plain
 * pipe/semicolon-delimited text and need only a browser User-Agent — no cookie
 * warm-up.
 *
 *   - {@link #fetchAllNav()}    : NAVAll.txt — every scheme's latest NAV plus the
 *                                 AMC/category section headers (the scheme master).
 *   - {@link #fetchNavHistory}  : the date-range history report — every scheme's
 *                                 NAVs across [from, to], used by the tiered seed.
 *
 * A blank/empty body is returned as {@link Optional#empty()} ("no data");
 * transport failures surface as {@link AmfiFetchException}.
 */
@Slf4j
@Component
public class AmfiClient {

    private static final String NAV_ALL_URL =
            "https://www.amfiindia.com/spages/NAVAll.txt";
    /** The history report serves the same delimited format for a date range. */
    private static final String NAV_HISTORY_URL =
            "https://portal.amfiindia.com/DownloadNAVHistoryReport_Po.aspx";
    private static final DateTimeFormatter DD_MMM_YYYY =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final RestClient restClient;

    public AmfiClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(20));
        factory.setReadTimeout(Duration.ofSeconds(90)); // history reports can be large
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                .defaultHeader("Accept", "*/*")
                .build();
    }

    /** Latest NAV for every AMFI scheme (NAVAll.txt). */
    public Optional<String> fetchAllNav() {
        return get(NAV_ALL_URL);
    }

    /** All-scheme NAV history for a date range (inclusive); callers chunk to keep it polite. */
    public Optional<String> fetchNavHistory(LocalDate from, LocalDate to) {
        String url = NAV_HISTORY_URL
                + "?frmdt=" + DD_MMM_YYYY.format(from)
                + "&todt=" + DD_MMM_YYYY.format(to);
        return get(url);
    }

    private Optional<String> get(String url) {
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return (body == null || body.isBlank()) ? Optional.empty() : Optional.of(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return Optional.empty();
            throw new AmfiFetchException("HTTP " + e.getStatusCode().value() + " from " + url, e);
        } catch (RestClientException e) {
            throw new AmfiFetchException("Request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    public static class AmfiFetchException extends RuntimeException {
        public AmfiFetchException(String message, Throwable cause) { super(message, cause); }
    }
}
