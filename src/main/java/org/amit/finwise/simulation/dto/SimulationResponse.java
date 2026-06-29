package org.amit.finwise.simulation.dto;
import java.util.List;
public record SimulationResponse(
    SimulationSummary summary,
    List<ChartPoint> history,
    ProjectionResult projection,
    List<EventAnnotation> annotations,
    FactorAttribution factorAttribution
) {}
