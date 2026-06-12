package org.amit.finwise.cfo.service.price;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Manages NSE India session cookies, shared across all NSE API consumers.
 * Establishes a session by hitting nseindia.com and captures Set-Cookie headers.
 * Caches for ~25 minutes before auto-refresh.
 */
@Slf4j
@Component
public class NseSessionManager {

    private static final String NSE_HOME_URL = "https://www.nseindia.com";

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private volatile String sessionCookies = null;
    private volatile LocalDateTime cookieExpiry = LocalDateTime.MIN;

    /**
     * Returns valid session cookies, refreshing if expired.
     * Thread-safe with lazy initialization and background invalidation.
     */
    public String getSessionCookies() throws PriceDataProvider.PriceProviderException {
        if (isValidSession()) {
            return sessionCookies;
        }
        synchronized (this) {
            if (isValidSession()) {
                return sessionCookies;
            }
            refreshSession();
            return sessionCookies;
        }
    }

    private boolean isValidSession() {
        return sessionCookies != null && LocalDateTime.now().isBefore(cookieExpiry);
    }

    /**
     * Invalidates the current session so the next call will refresh.
     * Use this when a request fails due to session expiry.
     */
    public void invalidateSession() {
        sessionCookies = null;
        cookieExpiry = LocalDateTime.MIN;
    }

    /**
     * Establishes a new NSE India session by hitting the homepage.
     * Captures Set-Cookie headers and caches them for 25 minutes.
     */
    private void refreshSession() throws PriceDataProvider.PriceProviderException {
        log.debug("[NSE] Establishing new session");
        try {
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(NSE_HOME_URL))
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();

            java.net.http.HttpResponse<String> response = httpClient.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString());

            List<String> cookies = response.headers().allValues("set-cookie");
            if (cookies.isEmpty()) {
                throw new PriceDataProvider.PriceProviderException("NSE India did not return any session cookies");
            }

            StringBuilder sb = new StringBuilder();
            for (String cookie : cookies) {
                String nameValue = cookie.split(";")[0].trim();
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(nameValue);
            }

            sessionCookies = sb.toString();
            cookieExpiry = LocalDateTime.now().plusMinutes(25);
            log.debug("[NSE] Session established, {} cookies captured", cookies.size());

        } catch (PriceDataProvider.PriceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PriceDataProvider.PriceProviderException("Failed to establish NSE session: " + e.getMessage(), e);
        }
    }
}
