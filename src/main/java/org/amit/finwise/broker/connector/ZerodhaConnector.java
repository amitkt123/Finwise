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
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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

        Map<?, ?> response = restClientBuilder.build()
            .post()
            .uri(KITE_BASE + "/session/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) response).get("data");
        String accessToken = (String) data.get("access_token");

        return BrokerConnection.builder()
            .userId(userId)
            .broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken(tokenEncryptionService.encrypt(accessToken))
            .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS)) // Kite tokens expire daily
            .status(ConnectionStatus.ACTIVE)
            .build();
    }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(KITE_BASE + "/portfolio/holdings")
            .header("Authorization", "token " + apiKey + ":" + decryptedAccessToken)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<?, ?>) response).get("data");
        if (data == null) return List.of();

        return data.stream().map(h -> new BrokerHoldingDTO(
            (String) h.get("isin"),
            (String) h.get("tradingsymbol"),
            (String) h.getOrDefault("instrument_name", (String) h.get("tradingsymbol")),
            BrokerEnum.ZERODHA,
            new BigDecimal(h.get("quantity").toString()),
            new BigDecimal(h.get("average_price").toString()),
            new BigDecimal(h.get("last_price").toString())
                .multiply(new BigDecimal(h.get("quantity").toString()))
        )).toList();
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
