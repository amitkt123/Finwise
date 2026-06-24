package org.amit.finwise.company.service;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.company.model.CompanyProfile;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 4 — Beginner narration layer.
 *
 * Turns z-scores, Piotroski F-scores, EWMA vol, RSI and other analytics into
 * one-line plain-English explainers per card: "what this means for a first-time
 * investor." Mirrors {@link org.amit.finwise.cfo.service.insight.InsightNarrationService}.
 *
 * Best-effort: if the LLM call fails, every card gets a safe templated fallback so
 * the profile always renders. The LLM is only allowed to interpret — it must not
 * introduce numbers not already in the profile (same constraint as InsightNarrationService).
 */
@Slf4j
@Service
public class CompanyBeginnerNarrationService {

    private final LLMProvider llmProvider;

    public CompanyBeginnerNarrationService(@Qualifier("refinementLlmProvider") LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    private static final String SYSTEM_PROMPT = """
            You are a personal finance educator helping a beginner investor in India understand
            a company's stock profile. You will receive a JSON snapshot of 6 analytical cards.

            For each card produce ONE plain-English sentence (max 120 chars) that explains what
            the card's most important finding means for a first-time investor. Use simple words.
            Do NOT introduce any number, %, or ₹ figure that is not already present in the input.

            Return ONLY a JSON object with these exact keys:
            "quote", "corporateActions", "ownership", "fundamentals", "riskFit", "newsPolicy"

            Each value is a single sentence string.
            """;

    public Map<String, String> narrate(CompanyProfile profile) {
        try {
            String userPrompt = buildPrompt(profile);
            String json = llmProvider.chatJson(SYSTEM_PROMPT, userPrompt);
            Map<String, String> parsed = parseNarrations(json);
            return fillMissing(parsed, profile);
        } catch (Exception e) {
            log.warn("[CompanyNarration] LLM call failed for {}: {}", profile.symbol(), e.getMessage());
            return buildFallbacks(profile);
        }
    }

    private String buildPrompt(CompanyProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("Symbol: ").append(profile.symbol()).append(", Sector: ").append(profile.sector()).append("\n\n");

        if (profile.quoteContext() != null) {
            CompanyProfile.QuoteContext q = profile.quoteContext();
            sb.append("QUOTE CARD: lastClose=").append(q.lastClose())
              .append(", dayChange=").append(q.dayChangePercent()).append("%")
              .append(", 52wHigh=").append(q.week52High())
              .append(", 52wLow=").append(q.week52Low())
              .append(", pricePercentileInRange=").append(q.pricePercentileInRange())
              .append(", volumeZScore=").append(q.volumeZScore()).append("\n");
        }
        if (profile.corporateActions() != null) {
            CompanyProfile.CorporateActionsCard ca = profile.corporateActions();
            sb.append("CORPORATE ACTIONS: pastActions=").append(ca.pastActions() != null ? ca.pastActions().size() : 0)
              .append(", forwardEvents=").append(ca.forwardEvents() != null ? ca.forwardEvents().size() : 0)
              .append(", postResultsDrift=").append(ca.postResultsDrift() != null ? ca.postResultsDrift().toString() : "none")
              .append("\n");
        }
        if (profile.ownership() != null) {
            CompanyProfile.OwnershipCard o = profile.ownership();
            sb.append("OWNERSHIP: promoterPledge=").append(o.latestPromoterPledgePct())
              .append("%, ownershipMomentum=").append(o.ownershipMomentumLabel())
              .append(", recentDeals=").append(o.recentDeals() != null ? o.recentDeals().size() : 0).append("\n");
        }
        if (profile.fundamentals() != null && profile.fundamentals().fundamentals() != null) {
            var f = profile.fundamentals().fundamentals();
            sb.append("FUNDAMENTALS: trailingPE=").append(f.getTrailingPE())
              .append(", piotroski=").append(f.getPiotroskiFScore())
              .append(", roe=").append(f.getRoe())
              .append(", valuationLabel=").append(f.getValuationLabel())
              .append(", peerVerdicts=").append(profile.fundamentals().peerVerdicts()).append("\n");
        }
        if (profile.riskFit() != null) {
            var r = profile.riskFit();
            String verdict = r.portfolioFit() != null ? r.portfolioFit().verdictLabel() : null;
            Object vol = r.riskMetrics() != null ? r.riskMetrics().ewmaVolatilityAnn() : null;
            sb.append("RISK: portfolioFitVerdict=").append(verdict)
              .append(", ewmaVol=").append(vol).append("\n");
        }
        if (profile.newsPolicy() != null) {
            sb.append("NEWS: articles=").append(profile.newsPolicy().news() != null ? profile.newsPolicy().news().size() : 0)
              .append(", policyExposures=").append(profile.newsPolicy().policyExposure() != null ? profile.newsPolicy().policyExposure().size() : 0)
              .append("\n");
        }
        return sb.toString();
    }

    private Map<String, String> parseNarrations(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        String[] keys = {"quote", "corporateActions", "ownership", "fundamentals", "riskFit", "newsPolicy"};
        for (String key : keys) {
            String val = extractField(json, key);
            if (val != null && !val.isBlank()) {
                result.put(key, val);
            }
        }
        return result;
    }

    private Map<String, String> fillMissing(Map<String, String> parsed, CompanyProfile profile) {
        Map<String, String> fallbacks = buildFallbacks(profile);
        Map<String, String> result = new LinkedHashMap<>(fallbacks);
        parsed.forEach(result::put);
        return result;
    }

    private Map<String, String> buildFallbacks(CompanyProfile profile) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("quote", "Shows today's price, 52-week range, and whether trading volume is unusual.");
        out.put("corporateActions", "Dividends, splits, and upcoming events like results or AGM.");
        out.put("ownership", "Who owns the company and whether big investors are buying or selling.");
        out.put("fundamentals", profile.fundamentals() != null && profile.fundamentals().fundamentals() != null
                ? "Company is " + profile.fundamentals().fundamentals().getValuationLabel() + " relative to its peers."
                : "Key financial ratios and valuation compared to similar companies.");
        out.put("riskFit", profile.riskFit() != null && profile.riskFit().portfolioFit() != null
                ? "Portfolio verdict: " + profile.riskFit().portfolioFit().verdictLabel() + "."
                : "How this stock would affect your portfolio's risk.");
        out.put("newsPolicy", "Recent news and regulatory changes that could affect this stock.");
        return out;
    }

    private String extractField(String json, String field) {
        if (json == null || json.isBlank()) return null;
        String key = "\"" + field + "\"";
        int idx = json.toLowerCase(java.util.Locale.ENGLISH).indexOf(key.toLowerCase(java.util.Locale.ENGLISH));
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        while (end > 0 && json.charAt(end - 1) == '\\') {
            end = json.indexOf('"', end + 1);
        }
        if (end < 0) return null;
        return json.substring(start + 1, end).replace("\\\"", "\"").replace("\\n", " ").trim();
    }
}
