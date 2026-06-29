package org.amit.finwise.simulation.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
public record SimulationRequest(
    String symbol,
    InstrumentType instrumentType,
    InvestmentMode investmentMode,
    BigDecimal amount,
    LocalDate startDate,
    int projectionMonths
) {}
