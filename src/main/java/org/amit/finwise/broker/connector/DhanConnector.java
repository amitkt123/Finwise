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
public class DhanConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;

    @Value("${broker.dhan.base-url:https://api.dhan.co}")
    private String baseUrl;

    @Override
    public BrokerEnum broker() { return BrokerEnum.DHAN; }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken, String brokerClientId) {
        Map<?, ?> response;
        try {
            response = restClientBuilder.build()
                .get()
                .uri(baseUrl + "/v2/holdings")
                .header("access-token", decryptedAccessToken)
                .retrieve()
                .body(Map.class);
        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Dhan access token expired/invalid. Re-auth required.");
            throw new IllegalStateException("Dhan token invalid. Please refresh it.", e);
        } catch (Exception e) {
            log.error("Failed to sync Dhan holdings: {}", e.getMessage());
            throw new IllegalStateException("Dhan holdings sync failed", e);
        }

        if (response == null) {
            log.warn("Dhan holdings API returned empty response");
            return List.of();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null) return List.of();

        List<BrokerHoldingDTO> holdings = new ArrayList<>();
        for (Map<String, Object> h : data) {
            Object isin = h.get("isin");
            Object tradingSymbol = h.get("tradingSymbol");
            Object quantity = h.get("totalQty");
            Object avgCostPrice = h.get("avgCostPrice");
            Object ltp = h.get("ltp");

            if (isin == null || tradingSymbol == null || quantity == null
                    || avgCostPrice == null || ltp == null) {
                log.warn("Skipping malformed Dhan holding (missing required field): {}", h);
                continue;
            }

            try {
                BigDecimal qty = new BigDecimal(quantity.toString());
                holdings.add(new BrokerHoldingDTO(
                    (String) isin,
                    (String) tradingSymbol,
                    (String) h.getOrDefault("securityId", (String) tradingSymbol),
                    BrokerEnum.DHAN,
                    qty,
                    new BigDecimal(avgCostPrice.toString()),
                    new BigDecimal(ltp.toString()).multiply(qty)
                ));
            } catch (Exception e) {
                log.warn("Skipping malformed Dhan holding (unparseable numeric field): {} — {}", h, e.getMessage());
            }
        }
        return holdings;
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        log.info("[Dhan] syncTransactions since {} — implement per Dhan API v2", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        // Dhan API keys don't expire; return as-is
        return connection;
    }
}
