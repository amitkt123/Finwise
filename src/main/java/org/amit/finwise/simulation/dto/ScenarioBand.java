package org.amit.finwise.simulation.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
public record ScenarioBand(
    LocalDate date,
    BigDecimal optimistic,
    BigDecimal neutral,
    BigDecimal pessimistic
) {}
