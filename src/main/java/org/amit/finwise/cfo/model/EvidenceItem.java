package org.amit.finwise.cfo.model;

import java.time.LocalDate;

/**
 * One computed or sourced fact used by the stock scorecard.
 */
public record EvidenceItem(
        String category,
        String metricName,
        String value,
        String interpretation,
        String direction,
        int confidence,
        String source,
        LocalDate asOf
) {}
