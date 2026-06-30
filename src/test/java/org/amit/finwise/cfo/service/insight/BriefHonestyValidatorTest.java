package org.amit.finwise.cfo.service.insight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Brief-wide honesty validator. The free-form brief prose is constrained only by a
 * system-prompt instruction; this validator gives it the same structural guarantee the
 * Insight Cards already have — a data-like figure (₹/%/decimal/grouped-amount) that does not
 * match any Java-computed value in the context fed to the LLM is treated as fabricated. The
 * offending sentence (or bullet/action line) is stripped and recorded in an auditable footer.
 */
class BriefHonestyValidatorTest {

    private final BriefHonestyValidator validator = new BriefHonestyValidator();

    private static final String CONTEXT = """
            ## Risk Decomposition
            Annualized Vol: 18.5%
            Portfolio Beta vs Nifty: 1.16
            1-Day VaR 95% (Cornish-Fisher): ₹12,000
            Top contributor HDFCBANK: 40.0% of volatility
            IT sector exposure: 8.43%
            """;

    @Test
    void stripsSentenceWithFabricatedFigure() {
        String brief = "## Risk Assessment\n"
                + "Portfolio beta is 1.16 vs Nifty, the top risk. "
                + "Your bank exposure could crater ₹52,000 next week.";

        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);

        assertTrue(r.sanitizedBrief().contains("1.16"), "verified figure must survive");
        assertFalse(r.sanitizedBrief().contains("52,000"), "fabricated ₹ figure must be stripped");
        assertEquals(1, r.redactions().size());
        assertTrue(r.redactions().get(0).contains("52,000"));
    }

    @Test
    void keepsFullyVerifiedProse() {
        String brief = "Annualized vol is 18.5% with portfolio beta 1.16 and a 1-day VaR of ₹12,000.";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertEquals(brief, r.sanitizedBrief());
        assertTrue(r.redactions().isEmpty());
    }

    @Test
    void toleratesRoundingAgainstContext() {
        // context says 8.43%; brief rounds to 8.4% — same figure, must survive.
        String brief = "IT exposure slipped to 8.4% of the book.";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertTrue(r.sanitizedBrief().contains("8.4%"));
        assertTrue(r.redactions().isEmpty());
    }

    @Test
    void whitelistsConfidenceScores() {
        // 0.7 is the model's own subjective score, not a data claim — never stripped even
        // though it is absent from the context.
        String brief = "- HDFCBANK trim: top risk contributor, beta=1.16 — Confidence: 0.7";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertTrue(r.sanitizedBrief().contains("Confidence: 0.7"));
        assertTrue(r.redactions().isEmpty());
    }

    @Test
    void dropsActionLineCarryingFabricatedBeta() {
        String brief = "- INFY add: cheap on a forward basis, beta=2.49 — Confidence: 0.6";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertFalse(r.sanitizedBrief().contains("2.49"), "fabricated beta line must be dropped whole");
        assertEquals(1, r.redactions().size());
    }

    @Test
    void preservesStructureAndSmallIntegers() {
        // Headers, list ordinals, "top 3-5", year tokens and horizon labels are not data claims.
        String brief = "## Section 1\n"
                + "1. Review your 3 active goals over 0-7 days.\n"
                + "Effective 2024-06-04 the policy changed.";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertEquals(brief, r.sanitizedBrief());
        assertTrue(r.redactions().isEmpty());
    }

    @Test
    void stripsFabricatedPercentButKeepsVerifiedNeighbourSentence() {
        String brief = "HDFCBANK is 40.0% of volatility. The portfolio yields 11.7% annually.";
        BriefHonestyValidator.Result r = validator.sanitize(brief, CONTEXT);
        assertTrue(r.sanitizedBrief().contains("40.0%"));
        assertFalse(r.sanitizedBrief().contains("11.7%"));
        assertEquals(1, r.redactions().size());
    }

    @Test
    void footerListsRedactionsAndIsOmittedWhenClean() {
        assertEquals("", BriefHonestyValidator.redactionFooter(List.of()));
        String footer = BriefHonestyValidator.redactionFooter(List.of("Risk — lose ₹52,000 soon"));
        assertTrue(footer.contains("redacted"));
        assertTrue(footer.contains("₹52,000"));
    }

    @Test
    void nullAndBlankAreSafe() {
        assertEquals("", validator.sanitize(null, CONTEXT).sanitizedBrief());
        assertEquals("  ", validator.sanitize("  ", CONTEXT).sanitizedBrief());
    }
}
