package org.amit.finwise.goal.model;

import java.util.List;

/**
 * Immutable output of a Monte-Carlo goal simulation.
 *
 * This is the uncertainty layer over {@code GoalAnalyzerService}'s deterministic PMT
 * headline: it answers "what is the chance of reaching the (inflation-adjusted) target
 * at the current SIP, and how much would it take for 75%/90% confidence?".
 *
 * All ₹ figures are nominal future values. The corpus percentiles describe the spread
 * of terminal wealth across the simulated paths.
 */
public record GoalSimulationResult(

        String mode,                    // "GBM" or "BOOTSTRAP"
        int paths,
        int months,                     // simulation horizon

        double annualDrift,             // μ used (fraction p.a.)
        double annualVolatility,        // σ used (fraction p.a.)
        boolean lowConfidence,          // inputs fell back to planning defaults

        double currentSip,              // monthly contribution the headline pSuccess assumes
        double inflationAdjustedTarget, // future-value target the corpus is compared against

        double probabilityOfSuccess,    // P(terminal corpus ≥ target) at currentSip

        double corpusP10,
        double corpusP25,
        double corpusP50,
        double corpusP75,
        double corpusP90,

        double requiredSip50,           // SIP for 50% confidence (NaN if unreachable in range)
        double requiredSip75,
        double requiredSip90,

        String headline,
        List<String> notes
) {}
