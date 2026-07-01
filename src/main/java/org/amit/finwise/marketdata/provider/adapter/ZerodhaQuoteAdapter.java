package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZerodhaQuoteAdapter implements MarketFeedProvider {

    private final RestClient.Builder restClientBuilder;

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Override public String name() { return "zerodha-quote"; }

    @Override
    public boolean supports(DataCapability c) {
        return c == DataCapability.REAL_TIME_QUOTE || c == DataCapability.HISTORICAL_OHLCV;
    }

    @Override
    public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }

    public DataEnvelope<LiveQuote> fetchQuote(String instrumentToken, String symbol, String accessToken) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://api.kite.trade/quote?i=NSE:" + symbol)
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve()
                .body(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) response)
                .get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> q = (Map<String, Object>) data.get("NSE:" + symbol);
            if (q == null) return DataEnvelope.missing(name(), "Symbol not found: " + symbol);

            // NOTE: Kite Connect's standard /quote endpoint does NOT return a true
            // 52-week high/low field — it only carries the day's OHLC and the
            // day's price-band circuit limits (upper_circuit_limit /
            // lower_circuit_limit), which are a +/-5-20% band around the previous
            // close, not a rolling 52-week range. Mapping circuit limits into
            // high52w/low52w (as an earlier draft of this adapter did) would
            // silently misrepresent real 52-week range data with an unrelated
            // number. Until a source that actually carries 52-week range is
            // wired in, we report these as null (not computed) rather than a
            // fabricated/incorrect value.
            LiveQuote quote = new LiveQuote(
                symbol,
                new BigDecimal(q.get("last_price").toString()),
                new BigDecimal(q.getOrDefault("net_change", "0").toString()),
                new BigDecimal(q.getOrDefault("change", "0").toString()),
                new BigDecimal(q.getOrDefault("volume", "0").toString()),
                null,
                null,
                Instant.now()
            );
            return DataEnvelope.of(quote, name(), DataQuality.LIVE);
        } catch (Exception e) {
            log.warn("[ZerodhaQuote] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
