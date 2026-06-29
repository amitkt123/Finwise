package org.amit.finwise.simulation.dto;
import java.math.BigDecimal;
import java.time.LocalDate;
public record MonteCarloInterval(LocalDate date, BigDecimal p5, BigDecimal p50, BigDecimal p95) {}
