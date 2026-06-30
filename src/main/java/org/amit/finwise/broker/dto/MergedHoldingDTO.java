package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;
import java.util.Map;

public record MergedHoldingDTO(
    String isin,
    String symbol,
    String name,
    BigDecimal totalQuantity,
    BigDecimal blendedAvgCost,
    BigDecimal totalCurrentValue,
    Map<BrokerEnum, BigDecimal> brokerBreakdown  // broker → quantity
) {}
