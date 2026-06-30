package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BrokerTransactionDTO(
    String isin,
    String symbol,
    BrokerEnum broker,
    String type,        // "BUY" | "SELL"
    BigDecimal quantity,
    BigDecimal price,
    LocalDate tradeDate
) {}
