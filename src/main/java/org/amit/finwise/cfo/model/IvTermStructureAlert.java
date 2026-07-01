package org.amit.finwise.cfo.model;

/** Near-term ATM IV exceeding far-term ATM IV on the live NSE option chain (BPR-4 gap-fill). */
public record IvTermStructureAlert(
    String symbol,
    double nearTermIvPct,
    double farTermIvPct,
    double inversionMagnitudePct,
    String interpretation
) {}
