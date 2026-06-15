package org.amit.finwise.cfo.model;

import java.util.List;

/**
 * A Java-authored numeric figure with full provenance.
 *
 * The value is the source of truth. InsightNarrationService keeps a set of all
 * Computation values per card and strips any LLM sentence that cites a number
 * outside that set (rounding-tolerant ±1%).  The LLM may explain meaning; it
 * may not recalculate.
 */
public record Computation(
        double value,
        String method,       // human label, e.g. "Cornish-Fisher 95% VaR", "GARCH Annualized Vol"
        List<String> inputs, // contributing symbols or factor names (empty if not applicable)
        String window,       // estimation window, e.g. "252d", "12m"; null if not applicable
        String unit          // "₹", "%", "x", or "" for dimensionless
) {}
