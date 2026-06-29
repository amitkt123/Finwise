package org.amit.finwise.simulation.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
public record SimulationSummary(
    String symbol,
    InstrumentType instrumentType,
    InvestmentMode investmentMode,
    BigDecimal totalInvested,
    BigDecimal currentValue,
    double absoluteReturnPct,
    double cagr,
    double xirr,
    LocalDate dataFrom,
    List<String> warnings,
    List<String> skipped
) {}
