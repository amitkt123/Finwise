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
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final TokenEncryptionService tokenEncryptionService;

    @Value("${broker.upstox.client-id:}")
    private String clientId;

    @Value("${broker.upstox.client-secret:}")
    private String clientSecret;

    @Value("${broker.upstox.redirect-uri:}")
    private String redirectUri;

    private static final String UPSTOX_BASE = "https://api.upstox.com/v2";

    @Override
    public BrokerEnum broker() { return BrokerEnum.UPSTOX; }

    public String buildAuthUrl() {
        return "https://api.upstox.com/v2/login/authorization/dialog"
            + "?response_type=code&client_id=" + clientId
            + "&redirect_uri=" + redirectUri;
    }

    public BrokerConnection exchangeCode(String userId, String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .post()
                .uri(UPSTOX_BASE + "/login/authorization/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException e) {
            log.error("Upstox token exchange HTTP error: {}", e.getMessage());
            throw new IllegalStateException("Upstox token exchange failed: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("Upstox token exchange failed: {}", e.getMessage());
            throw new IllegalStateException("Upstox token exchange failed", e);
        }

        if (response == null) {
            throw new IllegalStateException("Upstox token exchange returned an empty response");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        if (data == null) {
            throw new IllegalStateException("Upstox token exchange returned no data: " + response);
        }

        String accessToken = (String) data.get("access_token");
        if (accessToken == null) {
            throw new IllegalStateException("Upstox did not return an access_token");
        }

        return BrokerConnection.builder()
            .userId(userId).broker(BrokerEnum.UPSTOX)
            .encryptedAccessToken(tokenEncryptionService.encrypt(accessToken))
            .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
            .status(ConnectionStatus.ACTIVE)
            .build();
    }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken) {
        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .get().uri(UPSTOX_BASE + "/portfolio/long-term-holdings")
                .header("Authorization", "Bearer " + decryptedAccessToken)
                .retrieve().body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Upstox access token expired/invalid. Re-auth required.");
            throw new IllegalStateException("Upstox token invalid. Please refresh it.", e);
        } catch (Exception e) {
            log.error("Failed to sync Upstox holdings: {}", e.getMessage());
            throw new IllegalStateException("Upstox holdings sync failed", e);
        }

        if (response == null) {
            log.warn("Upstox holdings API returned empty response");
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
                log.warn("Skipping malformed Upstox holding (missing required field): {}", h);
                continue;
            }

            try {
                BigDecimal qty = new BigDecimal(quantity.toString());
                holdings.add(new BrokerHoldingDTO(
                    (String) isin,
                    (String) tradingsymbol,
                    (String) h.getOrDefault("company_name", (String) tradingsymbol),
                    BrokerEnum.UPSTOX,
                    qty,
                    new BigDecimal(avgPrice.toString()),
                    new BigDecimal(lastPrice.toString()).multiply(qty)
                ));
            } catch (Exception e) {
                log.warn("Skipping malformed Upstox holding (unparseable numeric field): {} — {}", h, e.getMessage());
            }
        }
        return holdings;
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        log.info("[Upstox] syncTransactions since {} — implement per Upstox API v2", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        connection.setStatus(ConnectionStatus.EXPIRED);
        return connection;
    }
}
