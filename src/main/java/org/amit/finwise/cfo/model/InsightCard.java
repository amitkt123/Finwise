package org.amit.finwise.cfo.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * A typed, self-contained insight with Java-authored numbers and an LLM-generated narration.
 *
 * Numbers are populated by InsightCardService generators before narration; the
 * InsightNarrationService then validates the LLM text against those numbers.
 * calibratedConfidence and trackRecord are reserved for Phase C.
 */
@Getter
@Builder
public class InsightCard {

    public enum Category {
        RISK, PERFORMANCE, TAX, GOAL, ALLOCATION, FACTOR, VOLATILITY, BACKTEST
    }

    public enum Severity {
        ALERT, INFO
    }

    private final Category category;
    private final Severity severity;
    private final String title;
    private final String headline;

    /** Java-authored figures — the validator's allow-list for the narration. */
    private final List<Computation> numbers;

    /** LLM-generated narration, stripped of any sentence containing an unauthorized number. */
    private final String narration;

    /** Per-stock cards only (marginalAdd, riskBudget); null otherwise. */
    private final String symbol;

    /** Phase C — null until calibrated. */
    private final Double calibratedConfidence;

    /** Phase C — null until calibrated. */
    private final Object trackRecord;
}
