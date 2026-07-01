package org.amit.finwise.broker.connector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.common.TokenEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final TokenEncryptionService tokenEncryptionService; // reuse AES encrypt/decrypt

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String apiSecret;

    @Value("${broker.zerodha.redirect-uri:}")
    private String redirectUri;

    private static final String KITE_BASE = "https://api.kite.trade";

    @Override
    public BrokerEnum broker() { return BrokerEnum.ZERODHA; }

    public String buildAuthUrl() {
        return "https://kite.zerodha.com/connect/login?api_key=" + apiKey + "&v=3";
    }

    public BrokerConnection exchangeRequestToken(String userId, String requestToken) {
        String checksum = computeChecksum(requestToken);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("api_key", apiKey);
        form.add("request_token", requestToken);
        form.add("checksum", checksum);

        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .post()
                .uri(KITE_BASE + "/session/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.error("Zerodha token exchange HTTP error: {}", e.getMessage());
            throw new IllegalStateException("Zerodha token exchange failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Zerodha token exchange failed: {}", e.getMessage());
            throw new IllegalStateException("Zerodha token exchange failed", e);
        }

        if (response == null) {
            throw new IllegalStateException("Zerodha token exchange returned an empty response");
        }

        // Kite returns {"status":"error","message":...} (no "data" key) for routine
        // failures like an invalid/expired request token or bad checksum.
        if ("error".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            String message = String.valueOf(response.get("message"));
            log.error("Zerodha token exchange returned error: {}", message);
            throw new IllegalStateException("Zerodha token exchange failed: " + message);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) {
            throw new IllegalStateException("Zerodha token exchange returned no data: " + response);
        }

        String accessToken = (String) data.get("access_token");
        if (accessToken == null) {
            throw new IllegalStateException("Zerodha did not return an access_token");
        }

        return BrokerConnection.builder()
            .userId(userId)
            .broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken(tokenEncryptionService.encrypt(accessToken))
            .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS)) // Kite tokens expire daily
            .status(ConnectionStatus.ACTIVE)
            .build();
    }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken, String brokerClientId) {
        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .get()
                .uri(KITE_BASE + "/portfolio/holdings")
                .header("Authorization", "token " + apiKey + ":" + decryptedAccessToken)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Zerodha access token expired/invalid. Re-auth required.");
            throw new IllegalStateException("Zerodha token invalid. Please refresh it.", e);
        } catch (Exception e) {
            log.error("Failed to sync Zerodha holdings: {}", e.getMessage());
            throw new IllegalStateException("Zerodha holdings sync failed", e);
        }

        if (response == null) {
            log.warn("Zerodha holdings API returned empty response");
            return List.of();
        }

        if ("error".equalsIgnoreCase(String.valueOf(response.get("status")))) {
            log.warn("Zerodha holdings API returned error status: {}", response.get("message"));
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) return List.of();

        List<BrokerHoldingDTO> holdings = new ArrayList<>();
        for (Map<String, Object> h : data) {
            Object isin = h.get("isin");
            Object tradingsymbol = h.get("tradingsymbol");
            Object quantity = h.get("quantity");
            Object avgPrice = h.get("average_price");
            Object lastPrice = h.get("last_price");

            if (isin == null || tradingsymbol == null || quantity == null
                    || avgPrice == null || lastPrice == null) {
                log.warn("Skipping malformed Zerodha holding (missing required field): {}", h);
                continue;
            }

            try {
                BigDecimal qty = new BigDecimal(quantity.toString());
                holdings.add(new BrokerHoldingDTO(
                    (String) isin,
                    (String) tradingsymbol,
                    (String) h.getOrDefault("instrument_name", (String) tradingsymbol),
                    BrokerEnum.ZERODHA,
                    qty,
                    new BigDecimal(avgPrice.toString()),
                    new BigDecimal(lastPrice.toString()).multiply(qty)
                ));
            } catch (Exception e) {
                log.warn("Skipping malformed Zerodha holding (unparseable numeric field): {} — {}", h, e.getMessage());
            }
        }
        return holdings;
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        // Kite Connect /orders endpoint for historical trades
        // Returns daily trade book — implement per Kite Connect API v3
        log.info("[Zerodha] syncTransactions since {} — implement per Kite API v3", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        // Kite tokens expire daily and require re-auth via OAuth; mark EXPIRED
        connection.setStatus(ConnectionStatus.EXPIRED);
        return connection;
    }

    String computeChecksum(String requestToken) {
        try {
            String input = apiKey + requestToken + apiSecret;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
