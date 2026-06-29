package org.amit.finwise.simulation.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
public record ChartPoint(LocalDate date, BigDecimal value) {}
