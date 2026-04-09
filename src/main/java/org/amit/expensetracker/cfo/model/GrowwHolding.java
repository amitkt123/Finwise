package org.amit.expensetracker.cfo.model;

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

        // Optional: Useful for distinguishing between what you own vs what is settled
        @JsonProperty("demat_free_quantity") BigDecimal tradableQuantity,
        @JsonProperty("t1_quantity") BigDecimal t1Quantity,

        // Optional: List of exchanges (NSE/BSE)
        @JsonProperty("tradable_exchanges") List<String> exchanges
) {}
