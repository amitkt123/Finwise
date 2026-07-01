package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;

public record GlobalMacroSnapshot(
    BigDecimal fedFundsRate,      // FEDFUNDS series
    BigDecimal dxy,               // DTWEXBGS (trade-weighted USD)
    BigDecimal crudePriceWti,     // DCOILWTICO
    BigDecimal goldPriceUsd,      // GOLDAMGBD228NLBM
    BigDecimal usVix,             // VIXCLS
    BigDecimal usTenYearYield,    // DGS10
    String dataDate               // latest observation date
) {}
