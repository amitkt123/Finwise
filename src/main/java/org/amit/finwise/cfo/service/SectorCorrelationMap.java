package org.amit.finwise.cfo.service;

import org.amit.finwise.cfo.model.NewsArticle;

import java.util.List;
import java.util.Map;

/**
 * Static lookup table encoding macro economic events → correlated sector impacts.
 *
 * Used by PersonalizedRelevanceScorer to boost relevance for portfolio sectors
 * that are economically linked to a news article's subject, even when the article
 * doesn't directly mention any portfolio stock or sector.
 *
 * Example: "Crude Oil surges" article → boosts relevance for users holding Asian Paints
 * (Paint sector) or IndiGo (Aviation), even if neither is mentioned by name.
 *
 * Impact strength scale:
 *   +2.0 = strongly positive for this sector
 *   +1.0 = moderately positive
 *   -1.0 = moderately negative
 *   -2.0 = strongly negative
 */
public final class SectorCorrelationMap {

    private SectorCorrelationMap() {}

    /**
     * A correlation rule: when an article matches the trigger condition,
     * apply the given sector impacts.
     *
     * @param keywords     One or more keywords to look for in article title (case-insensitive, OR logic)
     * @param categories   Article categories that qualify this rule (empty = any category)
     * @param sentiment    Article sentiment that qualifies this rule (null = any)
     * @param sectorImpacts Map of sector name → impact strength
     */
    public record CorrelationRule(
            List<String> keywords,
            List<String> categories,
            NewsArticle.Sentiment sentiment,
            Map<String, Double> sectorImpacts
    ) {}

    /**
     * All correlation rules. Evaluated in order; multiple rules can match.
     * Sector impacts from all matching rules are accumulated.
     */
    public static final List<CorrelationRule> RULES = List.of(

        // ── Oil & Energy ──────────────────────────────────────────────────────

        new CorrelationRule(
            List.of("crude oil", "oil price", "brent", "wti", "opec", "petroleum"),
            List.of(),
            NewsArticle.Sentiment.NEGATIVE,   // rising oil prices → negative for consumers
            Map.of(
                "Aviation",   -2.0,
                "Paint",      -1.5,
                "Tyres",      -1.5,
                "Chemicals",  -1.0,
                "FMCG",       -0.5,
                "Auto",       -1.0,
                "OMC",        +2.0,  // Oil Marketing Companies benefit from high prices
                "Energy",     +1.5
            )
        ),

        new CorrelationRule(
            List.of("crude oil", "oil price", "brent", "wti", "opec", "petroleum"),
            List.of(),
            NewsArticle.Sentiment.POSITIVE,   // falling oil prices → positive for consumers
            Map.of(
                "Aviation",   +2.0,
                "Paint",      +1.5,
                "Tyres",      +1.5,
                "Chemicals",  +1.0,
                "FMCG",       +0.5,
                "Auto",       +1.0,
                "OMC",        -1.5
            )
        ),

        // ── RBI / Interest Rates ──────────────────────────────────────────────

        new CorrelationRule(
            List.of("repo rate", "rate hike", "rate cut", "rbi", "monetary policy", "mpc"),
            List.of("POLICY"),
            NewsArticle.Sentiment.NEGATIVE,   // rate hike
            Map.of(
                "Banking",    -1.0,
                "Realty",     -2.0,
                "NBFC",       -1.5,
                "Auto",       -1.0,  // auto loans get costlier
                "Capital Markets", -0.5
            )
        ),

        new CorrelationRule(
            List.of("repo rate cut", "rate cut", "accommodative"),
            List.of("POLICY"),
            NewsArticle.Sentiment.POSITIVE,   // rate cut
            Map.of(
                "Banking",    +1.5,
                "Realty",     +2.0,
                "NBFC",       +1.5,
                "Auto",       +1.0,
                "Capital Markets", +1.0
            )
        ),

        // ── INR / Rupee ───────────────────────────────────────────────────────

        new CorrelationRule(
            List.of("rupee", "inr", "currency", "forex", "dollar index"),
            List.of(),
            NewsArticle.Sentiment.NEGATIVE,   // INR depreciation (rupee falls)
            Map.of(
                "IT",         +1.5,  // export revenue in USD
                "Pharma",     +1.0,  // export-heavy
                "Textiles",   +1.0,
                "Auto",       -1.5,  // import components costlier
                "Oil",        -1.5,  // oil imports more expensive
                "FMCG",       -0.5,  // imported ingredients
                "Capital Markets", -0.5
            )
        ),

        new CorrelationRule(
            List.of("rupee strengthens", "inr gains", "rupee appreciates"),
            List.of(),
            NewsArticle.Sentiment.POSITIVE,
            Map.of(
                "IT",         -1.0,  // USD earnings worth less in INR
                "Pharma",     -0.5,
                "Auto",       +1.0,  // cheaper imports
                "FMCG",       +0.5
            )
        ),

        // ── Monsoon / Agriculture ─────────────────────────────────────────────

        new CorrelationRule(
            List.of("monsoon", "rainfall", "drought", "imd forecast"),
            List.of(),
            NewsArticle.Sentiment.NEGATIVE,   // poor monsoon
            Map.of(
                "FMCG",       -1.0,
                "Agro",       -2.0,
                "Fertilizers",-1.5,
                "Irrigation", +1.0,  // demand for irrigation rises
                "Banking",    -0.5   // rural credit stress
            )
        ),

        new CorrelationRule(
            List.of("monsoon", "normal rainfall", "good monsoon"),
            List.of(),
            NewsArticle.Sentiment.POSITIVE,
            Map.of(
                "FMCG",       +1.0,
                "Agro",       +2.0,
                "Fertilizers",+1.0,
                "Banking",    +0.5
            )
        ),

        // ── Inflation / CPI ───────────────────────────────────────────────────

        new CorrelationRule(
            List.of("inflation", "cpi", "wpi", "price rise", "food inflation"),
            List.of("MACRO", "POLICY"),
            NewsArticle.Sentiment.NEGATIVE,
            Map.of(
                "FMCG",       -1.0,
                "Retail",     -1.0,
                "Banking",    -0.5,
                "Realty",     -0.5,
                "Gold",       +1.5   // inflation hedge
            )
        ),

        // ── Geopolitical / War / Conflict ─────────────────────────────────────

        new CorrelationRule(
            List.of("war", "conflict", "geopolitical", "sanctions", "middle east", "iran", "ukraine"),
            List.of("GEOPOLITICAL"),
            null,   // any sentiment
            Map.of(
                "Defence",    +2.0,
                "Energy",     +1.5,
                "Aviation",   -1.5,
                "Tourism",    -1.5,
                "Capital Markets", -1.0
            )
        ),

        // ── GDP / Economic Growth ─────────────────────────────────────────────

        new CorrelationRule(
            List.of("gdp", "economic growth", "gdp growth", "gdp forecast"),
            List.of("MACRO", "POLICY"),
            NewsArticle.Sentiment.NEGATIVE,
            Map.of(
                "Capital Markets", -1.5,
                "Banking",    -1.0,
                "Auto",       -1.0,
                "Realty",     -1.0,
                "FMCG",       -0.5
            )
        ),

        new CorrelationRule(
            List.of("gdp", "economic growth"),
            List.of("MACRO", "POLICY"),
            NewsArticle.Sentiment.POSITIVE,
            Map.of(
                "Capital Markets", +1.5,
                "Banking",    +1.0,
                "Auto",       +1.0,
                "Realty",     +1.0
            )
        ),

        // ── FII / FPI Flows ───────────────────────────────────────────────────

        new CorrelationRule(
            List.of("fii", "fpi", "foreign investor", "foreign outflow", "fii selling"),
            List.of(),
            NewsArticle.Sentiment.NEGATIVE,
            Map.of(
                "Capital Markets", -2.0,
                "Banking",    -1.0,
                "IT",         -1.0
            )
        ),

        new CorrelationRule(
            List.of("fii buying", "fii inflow", "fpi inflow", "foreign buying"),
            List.of(),
            NewsArticle.Sentiment.POSITIVE,
            Map.of(
                "Capital Markets", +2.0,
                "Banking",    +1.0,
                "IT",         +1.0
            )
        )
    );

    /**
     * Evaluates all rules against an article and returns the map of
     * accumulated sector impacts for matching rules.
     *
     * @param title      article title (lowercased internally)
     * @param category   article category string (e.g. "GEOPOLITICAL")
     * @param sentiment  article sentiment enum
     * @return map of sector → accumulated impact strength (can be negative or positive)
     */
    public static Map<String, Double> evaluate(String title, String category,
                                                NewsArticle.Sentiment sentiment) {
        java.util.Map<String, Double> accumulated = new java.util.HashMap<>();
        String lowerTitle = title == null ? "" : title.toLowerCase();

        for (CorrelationRule rule : RULES) {
            // Sentiment check (null rule sentiment = any)
            if (rule.sentiment() != null && rule.sentiment() != sentiment) continue;

            // Category check (empty list = any)
            if (!rule.categories().isEmpty() && !rule.categories().contains(category)) continue;

            // Keyword check (OR logic — any keyword in title triggers the rule)
            boolean keywordMatch = rule.keywords().stream()
                    .anyMatch(kw -> lowerTitle.contains(kw.toLowerCase()));
            if (!keywordMatch) continue;

            // Accumulate impacts from matching rule
            rule.sectorImpacts().forEach((sector, impact) ->
                    accumulated.merge(sector, impact, Double::sum));
        }

        return accumulated;
    }
}
