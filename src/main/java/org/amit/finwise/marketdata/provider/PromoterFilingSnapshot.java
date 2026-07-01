package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;

public record PromoterFilingSnapshot(
    String symbol,
    String quarterEndDate,
    BigDecimal promoterHoldingPct,
    BigDecimal promoterPledgedPct,   // pledged as % of promoter holding
    BigDecimal fiiHoldingPct,
    BigDecimal diiHoldingPct,
    BigDecimal retailHoldingPct
) {}
