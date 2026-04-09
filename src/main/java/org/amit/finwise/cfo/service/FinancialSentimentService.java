package org.amit.finwise.cfo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Tier 1: Rule-based financial sentiment classifier.
 *
 * Core principle: classify MARKET IMPACT for an Indian retail equity investor,
 * NOT the tone of the article. Examples:
 *   "IMF warns tokenized finance risks crises"  → NEGATIVE  (systemic risk)
 *   "Crude oil surges 5%"                       → NEGATIVE  (India = net importer)
 *   "RBI cuts repo rate by 25bps"               → POSITIVE  (equity positive)
 *   "Import duty on cotton reduced"             → NEUTRAL   (sector-specific)
 *   "HDFC Bank Q3 profit up 18%"                → POSITIVE  (earnings beat)
 *
 * Three analysis layers:
 *   1. Market-impact overrides   – high-confidence domain rules checked first
 *   2. Sentiment lexicon         – scored phrases with negation + context handling
 *   3. Category + impact mapping – determines impactType, impactHorizon, sectorImpact
 */
@Slf4j
@Service
public class FinancialSentimentService {

    // ══════════════════════════════════════════════════════════════════════════
    //  Layer 1 — Market Impact Overrides
    //  These trump lexicon scoring. Ordered by specificity (most specific first).
    // ══════════════════════════════════════════════════════════════════════════

    private record ImpactRule(String trigger, int score, String sectorImpact) {}

    /**
     * Patterns that carry unambiguous India-market impact regardless of tone.
     * Each maps a phrase to: sentiment delta, and optional per-sector impact.
     */
    private static final List<ImpactRule> MARKET_IMPACT_RULES = List.of(

        // ── Macro / systemic risk ────────────────────────────────────────────
        new ImpactRule("financial crisis",        -5, null),
        new ImpactRule("systemic risk",           -4, null),
        new ImpactRule("market crisis",           -5, null),
        new ImpactRule("warns of crisis",         -4, null),
        new ImpactRule("warns of risk",           -3, null),
        new ImpactRule("amplifying.*crisis",      -4, null),
        new ImpactRule("recession risk",          -4, null),
        new ImpactRule("sovereign default",       -5, null),
        new ImpactRule("global recession",        -4, null),
        new ImpactRule("financial contagion",     -4, null),

        // ── Geopolitical (negative for India macro) ──────────────────────────
        new ImpactRule("war breaks out",          -5, null),
        new ImpactRule("military strike",         -4, null),
        new ImpactRule("attacks on",              -4, null),   // "attacks on infrastructure/zone"
        new ImpactRule("attack on",               -4, null),
        new ImpactRule("airstrike",               -4, null),
        new ImpactRule("airstrikes",              -4, null),
        new ImpactRule("killed",                  -3, null),   // casualties = always negative
        new ImpactRule("casualties",              -3, null),
        new ImpactRule("wounded",                 -2, null),
        new ImpactRule("nuclear threat",          -5, null),
        new ImpactRule("sanctions imposed",       -3, null),
        new ImpactRule("ceasefire",                2, null),   // de-escalation = positive

        // ── Crude oil (India = net importer → inverse) ───────────────────────
        new ImpactRule("crude oil surge",         -3, "Energy:POSITIVE,Aviation:NEGATIVE,FMCG:NEGATIVE,Paint:NEGATIVE"),
        new ImpactRule("crude oil spike",         -3, "Energy:POSITIVE,Aviation:NEGATIVE,FMCG:NEGATIVE"),
        new ImpactRule("crude oil jump",          -2, "Energy:POSITIVE,Aviation:NEGATIVE"),
        new ImpactRule("crude oil drop",           2, "Energy:NEGATIVE,Aviation:POSITIVE,FMCG:POSITIVE"),
        new ImpactRule("crude oil fall",           2, "Energy:NEGATIVE,Aviation:POSITIVE,FMCG:POSITIVE"),
        new ImpactRule("brent surges",            -2, "Energy:POSITIVE,Aviation:NEGATIVE"),
        new ImpactRule("opec cuts",               -2, "Energy:POSITIVE,Aviation:NEGATIVE"),
        new ImpactRule("opec+ cuts",              -2, "Energy:POSITIVE,Aviation:NEGATIVE"),

        // ── Rupee (IT/Pharma export in USD → rupee depreciation = positive for them) ──
        new ImpactRule("rupee falls",              0, "IT:POSITIVE,Pharma:POSITIVE,Energy:NEGATIVE"),
        new ImpactRule("rupee weakens",            0, "IT:POSITIVE,Pharma:POSITIVE,Energy:NEGATIVE"),
        new ImpactRule("rupee depreciation",       0, "IT:POSITIVE,Pharma:POSITIVE,Energy:NEGATIVE"),
        new ImpactRule("rupee appreciates",        0, "IT:NEGATIVE,Pharma:NEGATIVE"),
        new ImpactRule("rupee strengthens",        0, "IT:NEGATIVE,Pharma:NEGATIVE"),

        // ── RBI / Monetary policy ────────────────────────────────────────────
        new ImpactRule("repo rate cut",            3, "Banking:POSITIVE,Realty:POSITIVE,Auto:POSITIVE"),
        new ImpactRule("rate cut",                 2, "Banking:POSITIVE,Realty:POSITIVE"),
        new ImpactRule("repo rate hike",          -2, "Banking:MIXED,Realty:NEGATIVE"),
        new ImpactRule("rate hike",               -2, "Banking:MIXED,Realty:NEGATIVE"),
        new ImpactRule("rbi cuts",                 3, "Banking:POSITIVE,Realty:POSITIVE"),
        new ImpactRule("liquidity crunch",        -3, "Banking:NEGATIVE"),
        new ImpactRule("liquidity surplus",        2, "Banking:POSITIVE"),
        new ImpactRule("cpr increased",           -1, "Banking:NEGATIVE"),
        new ImpactRule("crr increased",           -1, "Banking:NEGATIVE"),

        // ── FDA / Regulatory pharma ──────────────────────────────────────────
        new ImpactRule("fda approval",             3, "Pharma:POSITIVE"),
        new ImpactRule("fda import alert",        -3, "Pharma:NEGATIVE"),
        new ImpactRule("fda warning letter",      -3, "Pharma:NEGATIVE"),

        // ── Trade & tariffs ──────────────────────────────────────────────────
        // Import duty changes are sector-specific — do NOT score as market-wide positive.
        // e.g. "waive import duty on cotton" helps textile buyers but hurts domestic producers.
        // LLM (Tier 3) will assign sector-specific direction; keep Tier 1 neutral.
        new ImpactRule("import duty reduced",      0, null),
        new ImpactRule("import duty waived",       0, null),
        new ImpactRule("waive.*import duty",       0, null),  // matches "waive cotton import duty"
        new ImpactRule("import duty cut",          0, null),
        new ImpactRule("import duty increased",   -1, null),  // higher costs = negative bias
        new ImpactRule("cotton price",            -1, "Textile:NEGATIVE,FMCG:NEGATIVE"),  // raw material cost pressure
        new ImpactRule("windfall tax",            -1, "Energy:NEGATIVE"),  // penalises energy profits
        new ImpactRule("trade war escalates",     -3, "Metals:NEGATIVE,IT:NEGATIVE"),
        new ImpactRule("trade deal",               2, null),

        // ── Fed / US economy (affects FII flows into India) ──────────────────
        new ImpactRule("fed rate hike",           -2, "Banking:MIXED"),
        new ImpactRule("federal reserve hike",    -2, null),
        new ImpactRule("fed pivot",                2, null),
        new ImpactRule("us recession",            -3, "IT:NEGATIVE"),

        // ── Earnings signals (high confidence) ───────────────────────────────
        new ImpactRule("earnings beat",            3, null),
        new ImpactRule("beats estimates",          3, null),
        new ImpactRule("misses estimates",        -3, null),
        new ImpactRule("earnings miss",           -3, null),
        new ImpactRule("guidance raised",          3, null),
        new ImpactRule("guidance cut",            -3, null),
        new ImpactRule("profit warning",          -4, null)
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Layer 2 — Sentiment Lexicon (tone + negation)
    // ══════════════════════════════════════════════════════════════════════════

    private static final List<ScoredPhrase> SENTIMENT_LEXICON = buildLexicon();

    private static List<ScoredPhrase> buildLexicon() {
        List<ScoredPhrase> lexicon = new ArrayList<>();

        // Multi-word first (longer phrases take priority)
        lexicon.add(new ScoredPhrase("bear territory",        -4));
        lexicon.add(new ScoredPhrase("bear market",           -4));
        lexicon.add(new ScoredPhrase("sell-off",              -3));
        lexicon.add(new ScoredPhrase("sell off",              -3));
        lexicon.add(new ScoredPhrase("market crash",          -5));
        lexicon.add(new ScoredPhrase("flash crash",           -5));
        lexicon.add(new ScoredPhrase("circuit breaker",       -4));
        lexicon.add(new ScoredPhrase("lower circuit",         -4));
        lexicon.add(new ScoredPhrase("upper circuit",          3));
        lexicon.add(new ScoredPhrase("profit booking",        -1));
        lexicon.add(new ScoredPhrase("margin call",           -4));
        lexicon.add(new ScoredPhrase("debt default",          -5));
        lexicon.add(new ScoredPhrase("credit downgrade",      -4));
        lexicon.add(new ScoredPhrase("rating downgrade",      -4));
        lexicon.add(new ScoredPhrase("rating upgrade",         3));
        lexicon.add(new ScoredPhrase("record high",            4));
        lexicon.add(new ScoredPhrase("record low",            -4));
        lexicon.add(new ScoredPhrase("all-time high",          4));
        lexicon.add(new ScoredPhrase("all-time low",          -4));
        lexicon.add(new ScoredPhrase("52-week high",           3));
        lexicon.add(new ScoredPhrase("52-week low",           -3));
        lexicon.add(new ScoredPhrase("trade war",             -3));
        lexicon.add(new ScoredPhrase("supply chain disruption",-3));
        lexicon.add(new ScoredPhrase("npa rises",             -3));
        lexicon.add(new ScoredPhrase("bad loans rise",        -3));
        lexicon.add(new ScoredPhrase("inflation rises",       -2));
        lexicon.add(new ScoredPhrase("inflation falls",        2));

        // Single-word sentiment (lower priority)
        lexicon.add(new ScoredPhrase("rally",      3));
        lexicon.add(new ScoredPhrase("surge",      3));
        lexicon.add(new ScoredPhrase("soar",       3));
        lexicon.add(new ScoredPhrase("bullish",    3));
        lexicon.add(new ScoredPhrase("outperform", 2));
        lexicon.add(new ScoredPhrase("upgrade",    2));
        lexicon.add(new ScoredPhrase("growth",     1));
        lexicon.add(new ScoredPhrase("gain",       2));
        lexicon.add(new ScoredPhrase("profit",     1));
        lexicon.add(new ScoredPhrase("recover",    2));
        lexicon.add(new ScoredPhrase("strong",     1));
        lexicon.add(new ScoredPhrase("rise",       1));

        lexicon.add(new ScoredPhrase("crash",     -4));
        lexicon.add(new ScoredPhrase("rout",      -4));
        lexicon.add(new ScoredPhrase("plunge",    -4));
        lexicon.add(new ScoredPhrase("plummet",   -4));
        lexicon.add(new ScoredPhrase("bearish",   -3));
        lexicon.add(new ScoredPhrase("decline",   -2));
        lexicon.add(new ScoredPhrase("drop",      -2));
        lexicon.add(new ScoredPhrase("fall",      -1));
        lexicon.add(new ScoredPhrase("loss",      -2));
        lexicon.add(new ScoredPhrase("weak",      -2));
        lexicon.add(new ScoredPhrase("slump",     -3));
        lexicon.add(new ScoredPhrase("tumble",    -3));
        lexicon.add(new ScoredPhrase("warning",   -2));
        lexicon.add(new ScoredPhrase("warns",     -2));
        lexicon.add(new ScoredPhrase("risk",      -1));
        lexicon.add(new ScoredPhrase("volatile",  -1));
        lexicon.add(new ScoredPhrase("uncertain", -1));
        lexicon.add(new ScoredPhrase("downgrade", -3));
        lexicon.add(new ScoredPhrase("concern",   -1));
        lexicon.add(new ScoredPhrase("threat",    -2));
        lexicon.add(new ScoredPhrase("crisis",    -4));
        lexicon.add(new ScoredPhrase("default",   -4));

        // Sort longest-first so multi-word phrases take priority
        lexicon.sort((a, b) -> Integer.compare(b.phrase.length(), a.phrase.length()));
        return Collections.unmodifiableList(lexicon);
    }

    private static final Set<String> NEGATION_CUES = Set.of(
            "not", "no", "never", "neither", "nor", "n't",
            "fail", "failed", "fails", "failing",
            "unable", "unlikely", "lack", "lacks",
            "despite", "without"
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Layer 3 — Category Rules (priority-ordered, highest wins)
    // ══════════════════════════════════════════════════════════════════════════

    private static final List<CategoryRule> CATEGORY_RULES = List.of(

        new CategoryRule("IPO", 10, new String[]{
            "ipo", "initial public offering", "public issue", "listing date",
            "grey market premium", "gmp", "drhp", "draft red herring",
            "anchor investor", "price band", "oversubscribed",
            "allotment", "listing gains", "ipo allotment"
        }),

        new CategoryRule("EARNINGS", 9, new String[]{
            "quarterly result", "q1 result", "q2 result", "q3 result", "q4 result",
            "earnings", "net profit", "profit after tax", "pat",
            "revenue growth", "topline", "bottom line",
            "EBITDA", "ebit", "operating margin",
            "miss estimates", "beat estimates", "earnings miss", "earnings beat",
            "consolidated results", "standalone results", "guidance"
        }),

        new CategoryRule("ANALYST_RECOMMENDATION", 9, new String[]{
            "target price", "price target", "buy rating", "sell rating", "hold rating",
            "analyst upgrade", "analyst downgrade", "broker report",
            "initiates coverage", "overweight", "underweight", "outperform", "underperform",
            "strong buy", "strong sell", "recommended", "recommends",
            "stocks to buy", "stock to buy", "shares to buy",
            "buy or sell", "top picks", "stock picks", "stock recommendation",
            "which stock", "should you buy", "expert picks", "trading picks",
            "technical picks", "fundamental picks"
        }),

        new CategoryRule("CORPORATE_ACTION", 8, new String[]{
            "dividend", "interim dividend", "final dividend",
            "bonus share", "stock split", "buyback", "share buyback",
            "merger", "acquisition", "takeover", "demerger",
            "rights issue", "fpo", "ofs", "block deal", "bulk deal",
            "promoter stake", "pledge shares", "stake sale", "open offer"
        }),

        new CategoryRule("MACRO_RISK", 8, new String[]{
            "imf warns", "world bank warns", "systemic risk", "financial stability",
            "market contagion", "financial crisis", "amplifying.*risk", "amplifying.*crisis",
            "global financial", "tokenized.*risk", "crypto risk", "shadow banking risk",
            "too big to fail", "bank run", "capital flight"
        }),

        new CategoryRule("REGULATORY", 7, new String[]{
            "sebi order", "sebi directive", "sebi circular", "sebi penalty",
            "rbi directive", "rbi circular", "rbi order",
            "nclat", "nclt", "tribunal order", "court order",
            "insider trading", "front running", "market manipulation",
            "compliance notice", "show cause notice"
        }),

        new CategoryRule("POLICY", 7, new String[]{
            "rbi", "sebi", "irda", "pfrda",
            "budget", "union budget",
            "repo rate", "reverse repo", "slr", "crr",
            "monetary policy", "mpc", "policy decision",
            "fiscal policy", "government policy",
            "tax reform", "gst", "income tax", "corporate tax",
            "capital gains", "regulation", "ban", "restriction", "approval"
        }),

        new CategoryRule("GEOPOLITICAL", 6, new String[]{
            "war", "conflict", "sanction", "trade war", "tariff",
            "geopolit", "tension", "ceasefire",
            "ukraine", "russia", "china", "taiwan",
            "middle east", "israel", "iran", "pakistan",
            "nato", "opec", "opec+",
            "crude oil", "brent", "wti",
            "global markets", "global economy"
        }),

        new CategoryRule("MACRO", 5, new String[]{
            "inflation", "cpi", "wpi",
            "gdp", "economic growth", "iip",
            "unemployment", "jobless", "labour market",
            "interest rate",
            "bond yield", "10-year yield",
            "liquidity", "money supply",
            "forex", "foreign exchange",
            "rupee", "usd inr", "dollar index",
            "fed", "federal reserve", "ecb", "boj",
            "imf", "world bank", "global growth"
        }),

        new CategoryRule("SECTOR", 4, new String[]{
            "banking sector", "it sector", "pharma sector", "auto sector",
            "fmcg sector", "metal stocks", "psu stocks",
            "realty sector", "telecom sector", "energy sector",
            "sector rally", "sector outlook", "sector rotation"
        }),

        new CategoryRule("MARKET", 2, new String[]{
            "nifty", "sensex", "bank nifty",
            "bse", "nse",
            "market", "stock market",
            "shares", "stock", "equities",
            "intraday", "closing bell", "opening bell",
            "top gainers", "top losers",
            "midcap", "smallcap", "largecap",
            "mutual fund", "etf", "index fund",
            "advance decline", "market breadth"
        })
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Impact Type / Horizon mapping from category
    // ══════════════════════════════════════════════════════════════════════════

    private static final Map<String, String> CATEGORY_TO_IMPACT_TYPE = Map.ofEntries(
        Map.entry("IPO",                    "STOCK"),
        Map.entry("EARNINGS",               "STOCK"),
        Map.entry("ANALYST_RECOMMENDATION", "STOCK"),
        Map.entry("CORPORATE_ACTION",       "STOCK"),
        Map.entry("REGULATORY",             "REGULATORY"),
        Map.entry("POLICY",                 "POLICY"),
        Map.entry("MACRO_RISK",             "MACRO"),
        Map.entry("MACRO",                  "MACRO"),
        Map.entry("GEOPOLITICAL",           "MACRO"),
        Map.entry("SECTOR",                 "SECTOR"),
        Map.entry("MARKET",                 "MACRO"),
        Map.entry("GLOBAL",                 "MACRO")
    );

    private static final Map<String, String> CATEGORY_TO_IMPACT_HORIZON = Map.ofEntries(
        Map.entry("IPO",                    "SHORT_TERM"),
        Map.entry("EARNINGS",               "SHORT_TERM"),
        Map.entry("ANALYST_RECOMMENDATION", "SHORT_TERM"),
        Map.entry("CORPORATE_ACTION",       "SHORT_TERM"),
        Map.entry("REGULATORY",             "MEDIUM_TERM"),
        Map.entry("POLICY",                 "MEDIUM_TERM"),
        Map.entry("MACRO_RISK",             "MEDIUM_TERM"),
        Map.entry("MACRO",                  "MEDIUM_TERM"),
        Map.entry("GEOPOLITICAL",           "SHORT_TERM"),
        Map.entry("SECTOR",                 "MEDIUM_TERM"),
        Map.entry("MARKET",                 "SHORT_TERM"),
        Map.entry("GLOBAL",                 "MEDIUM_TERM")
    );

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    public SentimentResult analyse(String title, String summary) {
        String combined = (title + " " + summary).toLowerCase();

        // Layer 3: category first (needed for impact mapping)
        String category = classifyCategory(combined);

        // Layer 1: market-impact overrides (high-confidence rules)
        ImpactOverrideResult overrides = applyMarketImpactRules(combined);

        // Layer 2: lexicon-based sentiment scoring
        SentimentScore lexiconScore = computeLexiconSentiment(combined);

        // Merge: overrides add to lexicon score
        int totalScore = lexiconScore.rawScore + overrides.scoreDelta;

        String sentiment;
        if (totalScore >= 2)       sentiment = "POSITIVE";
        else if (totalScore <= -2) sentiment = "NEGATIVE";
        else                       sentiment = "NEUTRAL";

        // ── Geopolitical Conflict Resolver ────────────────────────────────────
        // Prevents "recovered" (airman rescue) from overriding the negative impact of
        // military attacks. If a GEOPOLITICAL article has combat signals, it cannot be POSITIVE.
        if ("GEOPOLITICAL".equals(category) && "POSITIVE".equals(sentiment)
                && hasCombatSignal(combined)) {
            sentiment = "NEGATIVE";
        }

        double confidence = computeConfidence(totalScore, overrides.hasOverride, combined);

        String impactType    = CATEGORY_TO_IMPACT_TYPE.getOrDefault(category, "MACRO");
        String impactHorizon = CATEGORY_TO_IMPACT_HORIZON.getOrDefault(category, "MEDIUM_TERM");

        // Merge sector impacts from overrides
        String sectorImpact = overrides.sectorImpact;

        return new SentimentResult(
                sentiment, totalScore, category, confidence,
                impactType, impactHorizon, sectorImpact
        );
    }

    // ── Layer 1: Market impact overrides ─────────────────────────────────────

    private ImpactOverrideResult    applyMarketImpactRules(String text) {
        int delta = 0;
        boolean hasOverride = false;
        String sectorImpact = null;

        for (ImpactRule rule : MARKET_IMPACT_RULES) {
            // Support simple regex-like patterns with .*
            boolean matches = rule.trigger.contains(".*")
                    ? text.matches(".*" + rule.trigger + ".*")
                    : text.contains(rule.trigger);

            if (matches) {
                delta += rule.score;
                hasOverride = true;
                if (rule.sectorImpact != null) {
                    // Merge sector impacts (last-write-wins per sector for simplicity)
                    sectorImpact = sectorImpact == null
                            ? rule.sectorImpact
                            : sectorImpact + "," + rule.sectorImpact;
                }
            }
        }
        return new ImpactOverrideResult(delta, hasOverride, sectorImpact);
    }

    // ── Layer 2: Lexicon sentiment ────────────────────────────────────────────

    private SentimentScore computeLexiconSentiment(String text) {
        int totalScore = 0;
        Set<Integer> matchedPositions = new HashSet<>();

        for (ScoredPhrase sp : SENTIMENT_LEXICON) {
            int idx = text.indexOf(sp.phrase);
            while (idx >= 0) {
                boolean overlap = false;
                for (int p = idx; p < idx + sp.phrase.length(); p++) {
                    if (matchedPositions.contains(p)) { overlap = true; break; }
                }
                if (!overlap) {
                    for (int p = idx; p < idx + sp.phrase.length(); p++) matchedPositions.add(p);
                    int score = isNegated(text, idx) ? -sp.score : sp.score;
                    totalScore += score;
                }
                idx = text.indexOf(sp.phrase, idx + sp.phrase.length());
            }
        }
        return new SentimentScore(totalScore);
    }

    private boolean isNegated(String text, int phraseStart) {
        int windowStart = Math.max(0, phraseStart - 60);
        String window = text.substring(windowStart, phraseStart).trim();
        String[] tokens = window.split("\\s+");
        int start = Math.max(0, tokens.length - 4);
        for (int i = start; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("[^a-z']", "");
            if (NEGATION_CUES.contains(token) || token.endsWith("n't")) return true;
        }
        return false;
    }

    // ── Layer 3: Category classifier ─────────────────────────────────────────

    private String classifyCategory(String text) {
        String bestCategory = "MARKET";
        double bestScore = 0;

        for (CategoryRule rule : CATEGORY_RULES) {
            int matchCount = 0;
            for (String kw : rule.keywords) {
                if (text.contains(kw)) matchCount++;
            }
            if (matchCount == 0) continue;

            double score = rule.priority * Math.log(1 + matchCount);
            if (score > bestScore) {
                bestScore = score;
                bestCategory = rule.name;
            }
        }
        return bestCategory;
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    /**
     * Confidence reflects how certain Tier 1 is about its classification.
     * Low confidence → candidate for LLM review (Tier 3).
     *
     * Factors that INCREASE confidence:
     *   - Strong sentiment score (clear directional)
     *   - Market-impact override matched (domain rule)
     *   - More text to analyse
     *
     * Factors that DECREASE confidence (= send to LLM):
     *   - Score close to zero (borderline neutral)
     *   - Conflicting positive + negative signals
     *   - No override matched (pure lexicon, less reliable)
     */
    private double computeConfidence(int totalScore, boolean hasOverride, String text) {
        double conf = 0.4;  // baseline — Tier 1 alone is moderately uncertain

        // Domain-rule override matched → more certain
        if (hasOverride) conf += 0.25;

        // Strong directional score → more certain
        int abs = Math.abs(totalScore);
        if      (abs >= 6) conf += 0.25;
        else if (abs >= 4) conf += 0.20;
        else if (abs >= 2) conf += 0.10;
        else               conf -= 0.10;  // borderline → uncertain

        // More text = more signals
        if (text.split("\\s+").length > 50) conf += 0.05;

        // Mixed signals → uncertain (good LLM candidate)
        boolean hasPositive = SENTIMENT_LEXICON.stream().anyMatch(sp -> sp.score > 0 && text.contains(sp.phrase));
        boolean hasNegative = SENTIMENT_LEXICON.stream().anyMatch(sp -> sp.score < 0 && text.contains(sp.phrase));
        if (hasPositive && hasNegative) conf -= 0.15;

        return Math.clamp(conf, 0.05, 0.95);
    }

    // ── Geopolitical combat signal detector ──────────────────────────────────

    private static final Set<String> COMBAT_SIGNALS = Set.of(
            "war", "attack", "killed", "wounded", "bomb", "strike", "missile",
            "explosion", "casualties", "airstrike", "troops", "offensive",
            "conflict", "shelling", "artillery"
    );

    private boolean hasCombatSignal(String text) {
        return COMBAT_SIGNALS.stream().anyMatch(text::contains);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Full analysis result from Tier 1.
     *
     * @param sentiment      Market impact: POSITIVE | NEGATIVE | NEUTRAL
     * @param rawScore       Aggregate numeric score (negative = bearish signal)
     * @param category       Primary event category
     * @param confidence     0.05–0.95; below 0.5 → candidate for LLM review
     * @param impactType     Who is impacted: MACRO | SECTOR | STOCK | POLICY | REGULATORY
     * @param impactHorizon  When impact materialises: SHORT_TERM | MEDIUM_TERM | LONG_TERM
     * @param sectorImpact   Per-sector directional string, e.g. "Banking:POSITIVE,Realty:NEGATIVE"
     */
    public record SentimentResult(
            String sentiment,
            int    rawScore,
            String category,
            double confidence,
            String impactType,
            String impactHorizon,
            String sectorImpact
    ) {}

    private record SentimentScore(int rawScore) {}
    private record ScoredPhrase(String phrase, int score) {}
    private record CategoryRule(String name, int priority, String[] keywords) {}
    private record ImpactOverrideResult(int scoreDelta, boolean hasOverride, String sectorImpact) {}
}
