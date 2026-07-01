package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InsiderTrade(
    String symbol,
    String personName,
    String designation,
    String tradeType,       // "BUY" | "SELL"
    BigDecimal quantity,
    BigDecimal price,
    LocalDate tradeDate
) {}
