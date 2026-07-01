package org.amit.finwise.broker.connector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;

    @Value("${broker.angel.api-key:}")
    private String apiKey;

    private static final String ANGEL_BASE = "https://apiconnect.angelone.in/rest/secure/angelbroking";

    @Override
    public BrokerEnum broker() { return BrokerEnum.ANGEL; }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedJwtToken, String brokerClientId) {
        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .get()
                .uri(ANGEL_BASE + "/portfolio/v1/getHolding")
                .header("Authorization", "Bearer " + decryptedJwtToken)
                .header("X-ClientCode", brokerClientId == null ? "" : brokerClientId)
                .header("X-PrivateKey", apiKey)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Angel access token expired/invalid. Re-auth required.");
            throw new IllegalStateException("Angel token invalid. Please refresh it.", e);
        } catch (Exception e) {
            log.error("Failed to sync Angel holdings: {}", e.getMessage());
            throw new IllegalStateException("Angel holdings sync failed", e);
        }

        if (response == null) {
            log.warn("Angel holdings API returned empty response");
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
            Object avgPrice = h.get("averageprice");
            Object ltp = h.get("ltp");

            if (isin == null || tradingsymbol == null || quantity == null
                    || avgPrice == null || ltp == null) {
                log.warn("Skipping malformed Angel holding (missing required field): {}", h);
                continue;
            }

            try {
                BigDecimal qty = new BigDecimal(quantity.toString());
                holdings.add(new BrokerHoldingDTO(
                    (String) isin,
                    (String) tradingsymbol,
                    (String) h.getOrDefault("symbolname", (String) tradingsymbol),
                    BrokerEnum.ANGEL,
                    qty,
                    new BigDecimal(avgPrice.toString()),
                    new BigDecimal(ltp.toString()).multiply(qty)
                ));
            } catch (Exception e) {
                log.warn("Skipping malformed Angel holding (unparseable numeric field): {} — {}", h, e.getMessage());
            }
        }
        return holdings;
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String token, LocalDate since) {
        log.info("[Angel] syncTransactions since {} — implement per Angel API", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        return connection; // Angel JWT refresh handled per their session API
    }
}
