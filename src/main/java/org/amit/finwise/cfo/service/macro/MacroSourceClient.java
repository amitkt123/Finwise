package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Optional;

/**
 * Thin, polite HTTP fetcher shared by the macro-source providers (FBIL / RBI /
 * MOSPI). Mirrors {@code NseArchiveClient}: a browser User-Agent, generous
 * timeouts, 404 → {@link Optional#empty()} ("no data for that date"), other
 * non-2xx and transport errors surfaced as a {@link MacroFetchException}.
 *
 * <p>Providers stay free of HTTP boilerplate and just parse the returned text.
 */
@Slf4j
@Component
public class MacroSourceClient {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestClient restClient;

    public MacroSourceClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", BROWSER_UA)
                .defaultHeader("Accept", "*/*")
                .build();
    }

    /** GET the URL as text; empty on 404, throws {@link MacroFetchException} otherwise. */
    public Optional<String> fetch(String url) {
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return (body == null || body.isBlank()) ? Optional.empty() : Optional.of(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return Optional.empty();
            throw new MacroFetchException("HTTP " + e.getStatusCode().value() + " from " + url, e);
        } catch (RestClientException e) {
            throw new MacroFetchException("Request failed for " + url + ": " + e.getMessage(), e);
        }
    }

    public static class MacroFetchException extends RuntimeException {
        public MacroFetchException(String message, Throwable cause) { super(message, cause); }
    }
}
