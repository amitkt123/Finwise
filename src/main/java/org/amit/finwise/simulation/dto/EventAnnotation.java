package org.amit.finwise.simulation.dto;
import java.time.LocalDate;
public record EventAnnotation(LocalDate date, String label, AnnotationType type) {}
