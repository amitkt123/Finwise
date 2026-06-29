package org.amit.finwise.simulation.dto;
import java.util.List;
public record ProjectionResult(
    List<ScenarioBand> scenarioBands,
    List<MonteCarloInterval> monteCarlo
) {}
