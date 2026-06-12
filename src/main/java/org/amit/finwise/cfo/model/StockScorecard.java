package org.amit.finwise.cfo.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Evidence-weighted stock recommendation scorecard.
 *
 * Scores are 0-100. Java computes every score; the LLM only explains the
 * evidence supplied here.
 */
public record StockScorecard(
        String symbol,
        LocalDate asOf,
        double totalScore,
        Recommendation recommendation,
        int confidence,
        int valuationScore,
        int qualityScore,
        int growthScore,
        int momentumScore,
        int riskScore,
        int newsMacroScore,
        int portfolioFitScore,
        String expectedReturnBand,
        List<String> bullCase,
        List<String> bearCase,
        List<String> keyRisks,
        List<String> dataGaps,
        List<EvidenceItem> evidenceItems
) {
    public enum Recommendation {
        BUY, WAIT, AVOID, NEEDS_MORE_DATA
    }
}
