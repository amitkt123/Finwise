package org.amit.finwise.cfo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GrowwHolding(
        @JsonProperty("isin") String isin,
        @JsonProperty("trading_symbol") String symbol,
        @JsonProperty("quantity") BigDecimal quantity,
        @JsonProperty("average_price") BigDecimal avgPrice,

        // Settlement / tradability
        @JsonProperty("demat_free_quantity") BigDecimal dematFreeQuantity,
        @JsonProperty("t1_quantity") BigDecimal t1Quantity,
        @JsonProperty("pledge_quantity") BigDecimal pledgeQuantity,

        // Exchanges (NSE/BSE)
        @JsonProperty("tradable_exchanges") List<String> exchanges
) {}
