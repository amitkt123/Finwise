package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;
import java.time.Instant;

public record LiveQuote(
    String symbol,
    BigDecimal lastPrice,
    BigDecimal change,
    BigDecimal changePct,
    BigDecimal volume,
    BigDecimal high52w,
    BigDecimal low52w,
    Instant timestamp
) {}
