package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;

public record BrokerHoldingDTO(
    String isin,
    String symbol,
    String name,
    BrokerEnum broker,
    BigDecimal quantity,
    BigDecimal avgCostPrice,
    BigDecimal currentValue
) {}
