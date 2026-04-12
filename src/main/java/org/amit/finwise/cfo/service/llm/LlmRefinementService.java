package org.amit.finwise.cfo.service.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tier 3: LLM-based refinement — runs AFTER the fetch cycle, asynchronously.
 *
 * ── What goes to Ollama (and what doesn't) ───────────────────────────────────
 *
 *  SENT to LLM (routed by NewsClassificationPipeline.shouldSendToLlm):
 *    ✓ GEOPOLITICAL / MACRO_RISK / MACRO articles with relevance >= 50
 *      (inherently complex multi-impact events — Tier 1 rules aren't enough)
 *    ✓ Articles with matched portfolio symbols AND Tier 1 confidence < 0.55
 *      (affects specific holdings — worth getting right)
 *    ✓ HIGH-relevance articles (score >= 70) with Tier 1 confidence < 0.55
 *
 *  SKIPPED (never sent):
 *    ✗ Relevance score < 30  (noise — not worth inference time)
 *    ✗ Already llmReviewed   (don't re-process)
 *    ✗ Anything Tier 1 is already confident about (confidence >= 0.55)
 *
 * ── Prompt design ────────────────────────────────────────────────────────────
 *
 *  The LLM is instructed to think as a PORTFOLIO ANALYST, not a text classifier.
 *  Key difference: classify MARKET IMPACT for an Indian equity investor, not tone.
 *  The prompt passes Tier 1 + Tier 2 results as context so the LLM refines,
 *  not redoes from scratch. Smaller, focused batches (max 4 for local Ollama).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmRefinementService {

    private final LLMProvider llmProvider;
    private final NewsArticleRepository newsArticleRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final org.amit.finwise.cfo.service.EmbeddingService embeddingService;

    @Value("${cfo.user.id}")
    private String userId;

    @Value("${cfo.llm.confidence-threshold:0.55}")
    private double confidenceThreshold;

    @Value("${cfo.llm.max-batch-size:4}")
    private int maxBatchSize;   // keep small for local Ollama; it has limited context

    @Value("${cfo.llm.enabled:true}")
    private boolean llmEnabled;

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Run after a news fetch cycle. Finds articles flagged for LLM review,
     * processes in small batches, applies refinements.
     * <p>
     * NOTE: @Async submits to a thread pool — the async proxy invokes the method directly,
     * bypassing Spring's @Transactional proxy. So @Transactional on an @Async method is a
     * no-op. Repository calls are self-transactional (Spring Data default), which is correct.
     */
    @Async("llmRefinementExecutor")
    public void refineRecentArticles() {
        if (!llmEnabled) {
            log.debug("LLM refinement disabled");
            return;
        }

        List<NewsArticle> candidates = findCandidates();
        if (candidates.isEmpty()) {
            log.debug("No articles need LLM review");
            return;
        }

        log.info("LLM refinement: {} articles queued ({} batches of up to {})",
                candidates.size(),
                (int) Math.ceil((double) candidates.size() / maxBatchSize),
                maxBatchSize);

        List<List<NewsArticle>> batches = partition(candidates, maxBatchSize);
        for (List<NewsArticle> batch : batches) {
            boolean success = false;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    List<RefinedClassification> refinements = callLlm(batch);
                    applyRefinements(batch, refinements);
                    success = true;
                    break;
                } catch (Exception e) {
                    if (attempt < 2) {
                        log.warn("LLM batch failed (attempt {}/2), retrying: {}", attempt, e.getMessage());
                    } else {
                        log.error("LLM batch failed after 2 attempts — skipping batch: {}", e.getMessage(), e);
                    }
                }
            }
            if (!success) {
                log.debug("Skipped batch of {} articles — will retry next scheduled cycle", batch.size());
            }
        }
    }

    @Transactional
    public List<RefinedClassification> refineArticles(List<NewsArticle> articles) {
        if (!llmEnabled || articles.isEmpty()) return List.of();
        return callLlm(articles.stream().limit(maxBatchSize).toList());
    }

    // ── Candidate selection ───────────────────────────────────────────────────

    private List<NewsArticle> findCandidates() {
        // Only look at recent articles (last 2 days) not yet LLM-reviewed
        List<NewsArticle> recent = newsArticleRepository
                .findRecentByRelevance(LocalDate.now().minusDays(2));

        return recent.stream()
                .filter(a -> !a.isLlmReviewed())
                // The pipeline already flagged needsLlmReview but we re-check here
                // using the same criteria so it's self-contained
                .filter(this::shouldReview)
                .limit((long) maxBatchSize * 3)  // 3 batches headroom
                .collect(Collectors.toList());
    }

    /**
     * Routing gate — mirrors NewsClassificationPipeline.shouldSendToLlm.
     * Explicit criteria so it's auditable in logs.
     */
    private boolean shouldReview(NewsArticle article) {
        int score = article.getRelevanceScore();
        double confidence = article.getTier1Confidence();
        String category = article.getCategory() != null ? article.getCategory().name() : "";

        // Never waste Ollama time on low-relevance noise
        if (score < 30) {
            return false;
        }

        // GEOPOLITICAL / MACRO_RISK / MACRO are complex — always review if relevant
        if (score >= 50 &&
                (category.equals("GEOPOLITICAL") || category.equals("MACRO_RISK") || category.equals("MACRO"))) {
            log.debug("LLM candidate [COMPLEX_CATEGORY]: '{}'", truncate(article.getTitle()));
            return true;
        }

        // Has specific symbols + Tier 1 uncertain → get it right
        if (article.getRelatedSymbols() != null && !article.getRelatedSymbols().isBlank()
                && confidence < confidenceThreshold) {
            log.debug("LLM candidate [SYMBOL+UNCERTAIN, conf={:.2f}]: '{}'",
                    String.format("%.2f", confidence), truncate(article.getTitle()));
            return true;
        }

        // High relevance + uncertain
        if (score >= 70 && confidence < confidenceThreshold) {
            log.debug("LLM candidate [HIGH_RELEVANCE+UNCERTAIN, score={}, conf={}]: '{}'",
                    score, String.format("%.2f", confidence), truncate(article.getTitle()));
            return true;
        }

        return false;
    }

    // ── LLM Interaction ───────────────────────────────────────────────────────

    private List<RefinedClassification> callLlm(List<NewsArticle> batch) {
        Set<String> holdings = getPortfolioSymbols();
        String content = llmProvider.chat(buildSystemPrompt(holdings), buildBatchPrompt(batch));
        return parseResponse(content, batch.size());
    }

    /**
     * Reads the latest portfolio snapshot and extracts the NSE symbols the user
     * currently holds. Used to personalise the LLM prompt — articles that touch
     * actual holdings get higher actionability scores.
     *
     * Returns an empty set (gracefully) if no snapshot exists or parsing fails.
     */
    private Set<String> getPortfolioSymbols() {
        try {
            return portfolioSnapshotRepository
                    .findTopByUserIdOrderBySnapshotTimeDesc(userId)
                    .map(this::parseSymbolsFromSnapshot)
                    .orElse(Set.of());
        } catch (Exception e) {
            log.debug("Could not load portfolio symbols for LLM context: {}", e.getMessage());
            return Set.of();
        }
    }

    private Set<String> parseSymbolsFromSnapshot(PortfolioSnapshot snapshot) {
        if (snapshot.getRawSnapshotJson() == null) return Set.of();
        try {
            // Structure: GrowwHoldingsResponse → payload.holdings[].trading_symbol
            JsonNode root = objectMapper.readTree(snapshot.getRawSnapshotJson());
            JsonNode holdings = root.path("payload").path("holdings");
            if (!holdings.isArray()) return Set.of();

            Set<String> symbols = new LinkedHashSet<>();
            for (JsonNode h : holdings) {
                String sym = h.path("trading_symbol").asText(null);
                if (sym != null && !sym.isBlank()) symbols.add(sym.toUpperCase());
            }
            return symbols;
        } catch (Exception e) {
            log.debug("Failed to parse holdings from snapshot: {}", e.getMessage());
            return Set.of();
        }
    }

    private String buildSystemPrompt(Set<String> userHoldings) {
        String holdingsList = userHoldings.isEmpty()
                ? "(portfolio not yet synced)"
                : String.join(", ", userHoldings);

        return """
                You are a SENIOR PORTFOLIO ANALYST at an Indian institutional desk.
                Your role: produce Bloomberg-grade financial intelligence, NOT text summarisation.

                ═══════════════════════════════════════════════════════════════════
                CRITICAL INSTRUCTION — READ BEFORE ANYTHING ELSE
                ═══════════════════════════════════════════════════════════════════
                You will receive "Gazetteer sectors" and "Tier 1 category" from an
                automated system. These are HINTS, not ground truth. They are
                sometimes WRONG. Your job is to CORRECT them, not justify them.

                ZERO-BASE SECTORING PROTOCOL (mandatory for every article):
                  Step 1 — From the HEADLINE and SUMMARY alone, identify which sectors
                            are ACTUALLY impacted. Ignore the gazetteer data at this step.
                  Step 2 — Compare your sectors with the "Gazetteer sectors" provided.
                  Step 3 — If they differ, use YOUR sectors and set confidence < 0.7
                            to signal the discrepancy.
                  Step 4 — Only include a sector in your output if you can identify a
                            SPECIFIC mechanism linking it to the article's core event.

                OVERRIDING BAD TIER-2 DATA (few-shot examples):

                Example A — Energy article wrongly tagged Pharma:
                  Input headline: "Govt imposes windfall tax on domestic crude production"
                  Gazetteer sectors: ["Pharma", "Energy"]
                  YOUR CORRECT output sectors: ["Energy"]
                  YOUR CORRECT output symbols:  ["ONGC", "RELIANCE", "CAIRN"]
                  Reason: Windfall tax is a levy on oil extraction profits. Pharma has
                          zero mechanistic link. The "Pharma" tag is a gazetteer noise hit.

                Example B — Rights issue article wrongly tagged Pharma:
                  Input headline: "Telecom firm raises ₹5,000 cr via rights issue"
                  Gazetteer sectors: ["Pharma", "Telecom"]
                  YOUR CORRECT output sectors: ["Telecom", "Capital Markets"]
                  Reason: "Issues" is not a Pharma keyword. Rights issue = equity dilution,
                          affects the specific company and broad capital markets sentiment.

                Example C — Geopolitical supply chain (second-order reasoning):
                  Input headline: "Qatar LNG facility shuts for maintenance"
                  Gazetteer sectors: ["Energy"]
                  YOUR CORRECT output sectors: ["Energy", "IT"]
                  Reason: LNG facilities use helium as a byproduct for semiconductor cooling.
                          IT hardware margins (TCS, INFY, HCLTECH) face input cost pressure.
                          This is second-order impact — include it with lower entity sentiment.

                ═══════════════════════════════════════════════════════════════════
                DUAL-SENTIMENT MODEL (mandatory)
                ═══════════════════════════════════════════════════════════════════
                Every article must carry TWO sentiment assessments:

                1. market_sentiment — price catalyst: will this move stock prices TODAY?
                   (This is what a trader cares about)

                2. fundamental_sentiment — underlying business reality: is the business
                   environment actually improving or deteriorating?
                   (This is what a long-term investor cares about)

                They CAN and often SHOULD differ:

                  "Textile industry urges govt for cotton import duty waiver"
                    market_sentiment     = POSITIVE  (hope of relief → short-term price pop)
                    fundamental_sentiment = NEGATIVE  (urgency of request signals surging input
                                                       costs are already hurting margins)

                  "RBI cuts repo rate by 25bps"
                    market_sentiment     = POSITIVE  (equity rally expected today)
                    fundamental_sentiment = POSITIVE  (cheaper credit genuinely aids growth)

                  "FDA warning letter to Sun Pharma plant"
                    market_sentiment     = NEGATIVE  (stock will drop today)
                    fundamental_sentiment = NEGATIVE  (manufacturing credibility impacted)

                ═══════════════════════════════════════════════════════════════════
                ENTITY-LEVEL SENTIMENT MAP (mandatory)
                ═══════════════════════════════════════════════════════════════════
                Do NOT assign one sentiment to the whole article.
                Return a map of EVERY entity/sector mentioned and its directional impact:

                  "HDFC Bank reports strong Q3, Realty sector faces pressure from rate hike fears"
                  entity_sentiments: {
                    "HDFCBANK": "POSITIVE",
                    "Banking":  "POSITIVE",
                    "Realty":   "NEGATIVE"
                  }

                Key: NSE symbol OR sector name. Value: "POSITIVE" | "NEGATIVE" | "NEUTRAL"

                ═══════════════════════════════════════════════════════════════════
                INDIA-SPECIFIC MARKET RULES
                ═══════════════════════════════════════════════════════════════════
                  • Crude oil rise    → NEGATIVE for India (net importer), POSITIVE for ONGC/OIL
                  • Rupee weakens     → POSITIVE for IT/Pharma exporters, NEGATIVE for importers
                  • Fed rate hike     → NEGATIVE (FII outflows from India)
                  • RBI rate cut      → POSITIVE for Banking, Realty, FMCG (consumption boost)
                  • IMF/World Bank warning → NEGATIVE (systemic risk)
                  • War/military      → NEGATIVE overall, POSITIVE for Defence
                  • FDA approval      → POSITIVE for that specific Pharma company only
                  • FDA warning       → NEGATIVE for that specific Pharma company only
                  • Windfall tax      → NEGATIVE for Energy upstream (ONGC, OIL, Cairn)
                  • PLI scheme        → POSITIVE for the targeted sector long-term
                  • Import duty cut   → NEGATIVE for domestic producers, POSITIVE for consumers

                ═══════════════════════════════════════════════════════════════════
                FIELD DEFINITIONS
                ═══════════════════════════════════════════════════════════════════
                Categories (most specific wins):
                  EARNINGS, IPO, CORPORATE_ACTION, ANALYST_RECOMMENDATION,
                  POLICY, REGULATORY, MACRO, MACRO_RISK, GEOPOLITICAL, SECTOR, MARKET, GLOBAL

                ImpactType:
                  STOCK=specific company | SECTOR=one sector | MACRO=whole market |
                  POLICY=govt action with direction | REGULATORY=circular no direction | MIXED=conflicting

                ImpactHorizon:
                  SHORT_TERM=1–5 days | MEDIUM_TERM=weeks–1Q | LONG_TERM=months–years

                SectorImpact string format: "Banking:POSITIVE,Realty:NEGATIVE,IT:NEUTRAL"

                Actionability (0–100):
                  80–100=act today | 50–79=track this week | 20–49=informational | 0–19=ignore

                PORTFOLIO CONTEXT — the investor currently holds these NSE symbols:
                  %s
                If this article DIRECTLY impacts any held symbol (positive or negative earnings,
                regulatory action, or rare valuation event), set actionability >= 85.
                If it is a macro/sector story that indirectly touches a held sector, use 55–75."""
                .formatted(holdingsList)
            + """


                ═══════════════════════════════════════════════════════════════════
                RESPONSE FORMAT — STRICT JSON ARRAY
                ═══════════════════════════════════════════════════════════════════
                Your ENTIRE response must be ONE valid JSON array and nothing else.
                Start with [ and end with ]. One JSON object per article indexed from 0.
                No markdown fences, no explanation, no prefix text, no trailing text.

                CRITICAL field rules:
                  - "symbols" is a flat list of strings: ["TCS", "INFY"]
                    NEVER nest lists. NEVER create fields like symbols_list or symbols_list_list.
                  - "sectors" is a flat list of strings: ["Banking", "IT"]
                  - "entity_sentiments" is a flat object: {"TCS": "POSITIVE", "IT": "NEUTRAL"}
                  - "market_sentiment", "fundamental_sentiment": exactly one of POSITIVE, NEGATIVE, NEUTRAL
                  - "category": one value from the list above (e.g. EARNINGS)
                  - "impact_type": one of MACRO, SECTOR, STOCK, POLICY, REGULATORY, MIXED
                  - "impact_horizon": one of SHORT_TERM, MEDIUM_TERM, LONG_TERM
                  - "actionability": integer 0–100
                  - "confidence": decimal 0.0–1.0

                Example for 2 articles:

                [
                  {
                    "id": 0,
                    "market_sentiment": "POSITIVE",
                    "fundamental_sentiment": "POSITIVE",
                    "entity_sentiments": {"HDFCBANK": "POSITIVE", "Banking": "POSITIVE"},
                    "category": "EARNINGS",
                    "impact_type": "STOCK",
                    "impact_horizon": "SHORT_TERM",
                    "sector_impact": "Banking:POSITIVE",
                    "symbols": ["HDFCBANK"],
                    "sectors": ["Banking"],
                    "impact_note": "HDFC Bank Q3 beat on NII and asset quality — near-term price catalyst.",
                    "actionability": 85,
                    "confidence": 0.88,
                    "tier2_overridden": false
                  },
                  {
                    "id": 1,
                    "market_sentiment": "NEGATIVE",
                    "fundamental_sentiment": "NEGATIVE",
                    "entity_sentiments": {"ONGC": "NEGATIVE", "Energy": "NEGATIVE"},
                    "category": "POLICY",
                    "impact_type": "SECTOR",
                    "impact_horizon": "MEDIUM_TERM",
                    "sector_impact": "Energy:NEGATIVE",
                    "symbols": ["ONGC", "OIL"],
                    "sectors": ["Energy"],
                    "impact_note": "Windfall tax extension cuts upstream margins for ONGC and OIL through Q4.",
                    "actionability": 70,
                    "confidence": 0.82,
                    "tier2_overridden": false
                  }
                ]
                """;
    }

    private String buildBatchPrompt(List<NewsArticle> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyse these ").append(batch.size()).append(" articles:\n\n");

        for (int i = 0; i < batch.size(); i++) {
            NewsArticle a = batch.get(i);
            sb.append("--- [").append(i).append("] ---\n");
            sb.append("Title: ").append(a.getTitle()).append("\n");

            if (a.getSummary() != null && !a.getSummary().isBlank()) {
                String s = a.getSummary().length() > 250
                        ? a.getSummary().substring(0, 250) + "..." : a.getSummary();
                sb.append("Summary: ").append(s).append("\n");
            }

            sb.append("Tier1 market_sentiment: ").append(a.getSentiment())
              .append(" (confidence: ").append(String.format("%.2f", a.getTier1Confidence())).append(")\n");
            sb.append("Tier1 category: ").append(a.getCategory()).append("\n");

            // Present Tier 2 data explicitly as UNVERIFIED HINTS, not ground truth.
            // The system prompt instructs the LLM to run zero-base sectoring first,
            // then compare and correct — not to justify what's listed here.
            if (a.getRelatedSymbols() != null && !a.getRelatedSymbols().isBlank()) {
                sb.append("⚠ Gazetteer symbols (UNVERIFIED — override if wrong): ")
                  .append(a.getRelatedSymbols()).append("\n");
            }
            if (a.getRelatedSectors() != null && !a.getRelatedSectors().isBlank()) {
                sb.append("⚠ Gazetteer sectors (UNVERIFIED — override if wrong): ")
                  .append(a.getRelatedSectors()).append("\n");
            }
            if (a.getSectorImpact() != null && !a.getSectorImpact().isBlank()) {
                sb.append("Tier1 sector impact (rule-based, verify): ").append(a.getSectorImpact()).append("\n");
            }

            // ── RAG: inject similar historical articles as grounding context ────
            // This is what separates generic analysis from evidence-based prediction.
            // Example: "Last time crude spiked during Middle East conflict, HUL missed estimates by 2.3%"
            List<org.amit.finwise.cfo.service.EmbeddingService.SimilarArticle> similar =
                    embeddingService.findSimilar(a.getTitle(), a.getSummary(), a.getId(), 2);

            if (!similar.isEmpty()) {
                sb.append("Historical context (similar past events — use this to ground your analysis):\n");
                for (var s : similar) {
                    sb.append("  [").append(s.publishedDate()).append("] ")
                      .append(s.title()).append("\n");
                    sb.append("  → Outcome: ").append(s.sentiment());
                    if (s.sectorImpact() != null && !s.sectorImpact().isBlank()) {
                        sb.append(", sector impact: ").append(s.sectorImpact());
                    }
                    if (s.relatedSymbols() != null && !s.relatedSymbols().isBlank()) {
                        sb.append(", symbols: ").append(s.relatedSymbols());
                    }
                    if (s.impactNote() != null && !s.impactNote().isBlank()) {
                        sb.append("\n  → Note: ").append(s.impactNote());
                    }
                    sb.append("\n");
                }
            }

            sb.append("\n");
        }

        return sb.toString();
    }

    // Matches the start of a real JSON array of objects, not just any '['.
    // Handles optional whitespace/newlines between '[' and '{'.
    private static final Pattern JSON_ARRAY_START = Pattern.compile("\\[\\s*\\{");

    private List<RefinedClassification> parseResponse(String content, int expectedCount) {
        String cleaned = extractJsonFromResponse(content);
        try {
            List<RefinedClassification> results =
                    objectMapper.readValue(cleaned, new TypeReference<>() {});
            if (results.size() != expectedCount) {
                log.warn("LLM returned {} results but expected {} — partial update will be applied",
                        results.size(), expectedCount);
            }
            return results;
        } catch (Exception firstError) {
            // ── Fallback 1: LLM returned a single object instead of an array ──────
            if (cleaned.startsWith("{")) {
                try {
                    RefinedClassification single =
                            objectMapper.readValue(cleaned, RefinedClassification.class);
                    log.warn("LLM returned a single object instead of an array — wrapping it");
                    return List.of(single);
                } catch (Exception ignored) {}
            }

            // ── Fallback 2: truncated JSON — recover complete objects ─────────────
            if (firstError.getMessage() != null && firstError.getMessage().contains("end-of-input")) {
                log.warn("LLM response appears truncated, attempting partial recovery...");
                try {
                    // Find the last fully-closed object: ends with "}," or is the only object
                    int lastCommaClose = cleaned.lastIndexOf("},");
                    if (lastCommaClose > 0) {
                        String truncFixed = cleaned.substring(0, lastCommaClose + 1) + "]";
                        List<RefinedClassification> partial =
                                objectMapper.readValue(truncFixed, new TypeReference<>() {});
                        log.info("Recovered {}/{} results from truncated LLM response", partial.size(), expectedCount);
                        return partial;
                    }
                } catch (Exception ignored) {}
            }

            log.warn("Failed to parse LLM response ({}). Raw response (first 500 chars): {}",
                    firstError.getMessage(),
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);
            return List.of();
        }
    }

    /**
     * Strips markdown fences, think-tags, and leading non-JSON text, then locates
     * the start of the JSON array or object in the LLM response.
     */
    private static String extractJsonFromResponse(String content) {
        String cleaned = content.trim();

        // Strip markdown fences (```json ... ```)
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
        }

        // Strip <think>...</think> that reasoning models (e.g. DeepSeek) emit before JSON
        cleaned = cleaned.replaceAll("(?s)<think>.*?</think>", "").trim();

        // Find the FIRST real JSON array-of-objects (not an index like "[5]")
        // This guards against responses like "Here are results for [3] articles:\n[{..."
        Matcher m = JSON_ARRAY_START.matcher(cleaned);
        if (m.find()) {
            cleaned = cleaned.substring(m.start());
        } else if (cleaned.contains("{")) {
            // No array start found — might be a bare object; trim to first {
            cleaned = cleaned.substring(cleaned.indexOf('{'));
        }

        return cleaned;
    }

    // ── Apply refinements ─────────────────────────────────────────────────────

    private void applyRefinements(List<NewsArticle> batch, List<RefinedClassification> refinements) {
        int updated = 0;

        for (RefinedClassification r : refinements) {
            if (r.id < 0 || r.id >= batch.size()) continue;

            // Sanitize symbols — reject garbage like "symbols_list_list_list_..."
            // NSE symbols are 1-20 chars, uppercase alphanumeric with optional - or &
            if (r.symbols != null) {
                r.symbols = r.symbols.stream()
                        .filter(s -> s != null
                                && !s.contains("_list")
                                && !s.contains("_list_")
                                && s.length() <= 20
                                && s.matches("[A-Z0-9&.\\-]+"))
                        .distinct()
                        .toList();
            }

            // Sanitize sectors — reject obviously malformed values
            if (r.sectors != null) {
                r.sectors = r.sectors.stream()
                        .filter(s -> s != null && !s.contains("_list") && s.length() <= 50)
                        .distinct()
                        .toList();
            }

            NewsArticle article = batch.get(r.id);
            boolean changed = false;

            // Only override Tier 1 if LLM is more confident
            if (r.confidence >= 0.6) {

                // Market sentiment (price catalyst) — replaces the single "sentiment" field
                String effectiveSentiment = r.effectiveMarketSentiment();
                if (effectiveSentiment != null) {
                    try {
                        NewsArticle.Sentiment newSentiment = NewsArticle.Sentiment.valueOf(effectiveSentiment);
                        if (article.getSentiment() != newSentiment) {
                            article.setSentiment(newSentiment);
                            changed = true;
                        }
                    } catch (IllegalArgumentException ignored) {}
                }

                // Fundamental sentiment (business reality — dual-sentiment model)
                if (r.fundamentalSentiment != null && !r.fundamentalSentiment.isBlank()) {
                    try {
                        NewsArticle.Sentiment fundamental =
                                NewsArticle.Sentiment.valueOf(r.fundamentalSentiment);
                        article.setFundamentalSentiment(fundamental);
                        changed = true;
                    } catch (IllegalArgumentException ignored) {}
                }

                // Category
                if (r.category != null) {
                    try {
                        NewsArticle.Category newCat = NewsArticle.Category.valueOf(r.category);
                        if (article.getCategory() != newCat) {
                            article.setCategory(newCat);
                            changed = true;
                        }
                    } catch (IllegalArgumentException ignored) {}
                }

                // ImpactType
                if (r.impactType != null) {
                    try {
                        article.setImpactType(NewsArticle.ImpactType.valueOf(r.impactType));
                        changed = true;
                    } catch (IllegalArgumentException ignored) {}
                }

                // ImpactHorizon
                if (r.impactHorizon != null) {
                    try {
                        article.setImpactHorizon(NewsArticle.ImpactHorizon.valueOf(r.impactHorizon));
                        changed = true;
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            // Symbols: merge (Tier 2 gazetteer + LLM additions)
            if (r.symbols != null && !r.symbols.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>();
                if (article.getRelatedSymbols() != null && !article.getRelatedSymbols().isBlank()) {
                    merged.addAll(Arrays.asList(article.getRelatedSymbols().split(",")));
                }
                merged.addAll(r.symbols);
                String mergedStr = String.join(",", merged);
                if (!mergedStr.equals(article.getRelatedSymbols())) {
                    article.setRelatedSymbols(mergedStr);
                    changed = true;
                }
            }

            // Sectors: merge
            if (r.sectors != null && !r.sectors.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>();
                if (article.getRelatedSectors() != null && !article.getRelatedSectors().isBlank()) {
                    merged.addAll(Arrays.asList(article.getRelatedSectors().split(",")));
                }
                merged.addAll(r.sectors);
                article.setRelatedSectors(String.join(",", merged));
                changed = true;
            }

            // Sector impact (LLM is authoritative here — it's more nuanced)
            if (r.sectorImpact != null && !r.sectorImpact.isBlank()) {
                article.setSectorImpact(r.sectorImpact);
                changed = true;
            }

            // Entity-level sentiment map (Bloomberg-grade: per-entity direction)
            if (r.entitySentiments != null && !r.entitySentiments.isEmpty()) {
                try {
                    // Store as compact JSON string in the TEXT column
                    ObjectMapper om = new ObjectMapper();
                    article.setEntitySentiments(om.writeValueAsString(r.entitySentiments));
                    changed = true;
                } catch (Exception e) {
                    log.warn("Failed to serialize entity sentiments: {}", e.getMessage());
                }
            }

            // Impact note for CFO brief
            if (r.impactNote != null && !r.impactNote.isBlank()) {
                article.setLlmImpactNote(r.impactNote);
                changed = true;
            }

            // Log when the LLM overrode bad Tier 2 data — this is the critical audit trail
            if (r.tier2Overridden) {
                log.info("LLM corrected Tier2 data for '{}': old_sectors={}, new_sectors={}",
                        truncate(article.getTitle()),
                        article.getRelatedSectors(),
                        r.sectors != null ? String.join(",", r.sectors) : "none");
            }

            // Actionability
            if (r.actionability > 0) {
                article.setActionabilityScore(r.actionability);
                changed = true;
            }

            // Always mark as llmReviewed so processed articles are not re-queued.
            // Relevance boost only when meaningful content changed.
            article.setLlmReviewed(true);
            if (changed) {
                article.setRelevanceScore(Math.min(100, article.getRelevanceScore() + 10));
                updated++;
                log.debug("LLM updated [{}]: market={}, fundamental={}, category={}, actionability={}",
                        truncate(article.getTitle()), article.getSentiment(),
                        article.getFundamentalSentiment(), article.getCategory(),
                        article.getActionabilityScore());
            }
            newsArticleRepository.save(article);
        }

        log.info("LLM refinement complete: {}/{} articles updated in batch of {}",
                updated, batch.size(), batch.size());
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            batches.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return batches;
    }

    private String truncate(String s) {
        return s != null && s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    // ── LLM response DTO ──────────────────────────────────────────────────────

    public static class RefinedClassification {
        public int    id;

        /** Price catalyst sentiment — will this move the stock price today? */
        @JsonProperty("market_sentiment")
        public String marketSentiment;

        /**
         * Underlying business reality — is the fundamental situation good or bad?
         * Can differ from marketSentiment (e.g. duty waiver plea = market POSITIVE,
         * fundamental NEGATIVE because it signals margin pain).
         */
        @JsonProperty("fundamental_sentiment")
        public String fundamentalSentiment;

        /**
         * Entity-level sentiment map.
         * e.g. {"TATASTEEL":"POSITIVE","Pharma":"NEGATIVE","HDFCBANK":"POSITIVE"}
         * Bloomberg-grade: one article → many entity-specific directions.
         */
        @JsonProperty("entity_sentiments")
        public Map<String, String> entitySentiments;

        public String category;
        public List<String> symbols;
        public List<String> sectors;
        public double confidence;

        @JsonProperty("impact_type")      public String  impactType;
        @JsonProperty("impact_horizon")   public String  impactHorizon;
        @JsonProperty("sector_impact")    public String  sectorImpact;
        @JsonProperty("impact_note")      public String  impactNote;
        @JsonProperty("actionability")    public int     actionability;
        @JsonProperty("tier2_overridden") public boolean tier2Overridden;

        // Legacy fallback: older LLM responses may still use "sentiment"
        public String sentiment;

        /** Returns the effective market sentiment, preferring the explicit field. */
        public String effectiveMarketSentiment() {
            if (marketSentiment != null && !marketSentiment.isBlank()) return marketSentiment;
            return sentiment;  // backward-compat with old prompt schema
        }

        public RefinedClassification() {}
    }
}
