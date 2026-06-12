package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live RBI policy repo rate scraper (Phase 6b). OFF by default
 * (cfo.macro.live-rbi-enabled=false) — the configured cfo.macro.repo-rate
 * remains the source of truth; a live reading that disagrees only produces
 * a data-quality note nudging the user to update the config.
 *
 * Scrapes the RBI homepage "Current Rates" panel with a strict regex; any
 * structure change, parse miss, or value outside the 3–10% sanity band is
 * discarded as a failed read (Optional.empty), never propagated.
 */
@Slf4j
@Service
public class RbiRateProvider {

    private static final String RBI_HOME_URL = "https://www.rbi.org.in";
    private static final Pattern REPO_RATE_PATTERN =
            Pattern.compile("Policy\\s+Repo\\s+Rate\\s*:?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*%",
                    Pattern.CASE_INSENSITIVE);
    private static final double MIN_SANE_RATE = 3.0;
    private static final double MAX_SANE_RATE = 10.0;

    private final boolean enabled;
    private final HttpClient httpClient;

    public RbiRateProvider(@Value("${cfo.macro.live-rbi-enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Fetches the live policy repo rate from the RBI homepage.
     * Returns empty when disabled, unreachable, unparseable, or out of the
     * 3–10% sanity band.
     */
    public Optional<BigDecimal> fetchLiveRepoRate() {
        if (!enabled) return Optional.empty();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RBI_HOME_URL))
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body() == null) {
                log.warn("[RBI] Homepage fetch returned status {}", response.statusCode());
                return Optional.empty();
            }
            return extractRepoRate(response.body());
        } catch (Exception e) {
            log.warn("[RBI] Live repo rate fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Strict regex extraction with sanity band. Package-private for testing. */
    Optional<BigDecimal> extractRepoRate(String html) {
        Matcher matcher = REPO_RATE_PATTERN.matcher(html);
        if (!matcher.find()) {
            log.warn("[RBI] 'Policy Repo Rate' pattern not found — page structure may have changed");
            return Optional.empty();
        }
        BigDecimal rate = new BigDecimal(matcher.group(1));
        double v = rate.doubleValue();
        if (v < MIN_SANE_RATE || v > MAX_SANE_RATE) {
            log.warn("[RBI] Scraped repo rate {}% outside sanity band [{}–{}]%, discarding",
                    v, MIN_SANE_RATE, MAX_SANE_RATE);
            return Optional.empty();
        }
        return Optional.of(rate);
    }
}
