package org.amit.finwise.simulation.dto;
public record FactorAttribution(
    double periodReturnPct,
    double marketBetaPct,
    double alphaPct,
    double unexplainedPct
) {}
