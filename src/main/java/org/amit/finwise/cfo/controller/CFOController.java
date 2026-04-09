package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.*;
import org.amit.finwise.cfo.repository.RiskQuestionnaireRepository;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.amit.finwise.cfo.service.GrowwAuthService;
import org.amit.finwise.cfo.service.InvestorBehaviorService;
import org.amit.finwise.cfo.service.PersonalizedRelevanceScorer;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.ingestion.GrowwConnector;
import org.amit.finwise.cfo.service.ingestion.NewsAggregatorService;
import org.amit.finwise.cfo.service.llm.LlmRefinementService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/cfo")
@RequiredArgsConstructor
public class CFOController {

    private final CFOAdvisorService cfoAdvisorService;
    private final GrowwAuthService growwAuthService;
    private final GrowwConnector growwConnector;
    private final NewsAggregatorService newsAggregatorService;
    private final LlmRefinementService llmRefinementService;
    private final PersonalizedRelevanceScorer personalizedRelevanceScorer;
    private final InvestorBehaviorService investorBehaviorService;
    private final RiskQuestionnaireRepository riskQuestionnaireRepository;
    private final StockPriceService stockPriceService;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    // ── Daily Brief ────────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/brief
     * Returns today's CFO morning brief (generates on demand if not yet created).
     */
    @GetMapping("/brief")
    public ResponseEntity<AiInsight> getDailyBrief() {
        AiInsight brief = cfoAdvisorService.generateDailyBrief();
        return ResponseEntity.ok(brief);
    }

    // ── Insights ──────────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/insights?page=0&size=20
     * Paginated list of all CFO insights.
     */
    @GetMapping("/insights")
    public ResponseEntity<Page<AiInsight>> getInsights(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(cfoAdvisorService.getInsights(page, size));
    }

    /**
     * GET /api/cfo/insights/{type}
     * Latest insight of a specific type (DAILY_BRIEF, GOAL_ADVICE, MARKET_INSIGHT, etc.)
     */
    @GetMapping("/insights/{type}")
    public ResponseEntity<AiInsight> getInsightByType(@PathVariable AiInsight.InsightType type) {
        return cfoAdvisorService.getLatestInsightByType(type)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Portfolio ─────────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/portfolio
     * Latest portfolio snapshot with P&L.
     */
    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSnapshot> getLatestPortfolio() {
        return cfoAdvisorService.getLatestPortfolioSnapshot()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/cfo/sync/groww
     * Trigger a manual Groww portfolio sync.
     */
    @PostMapping("/sync/groww")
    public ResponseEntity<PortfolioSnapshot> syncGroww() {
        PortfolioSnapshot snapshot = growwConnector.syncHoldings();
        return ResponseEntity.ok(snapshot);
    }


    // ── News ──────────────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/news?limit=20
     * Latest news ranked by portfolio relevance.
     */
    @GetMapping("/news")
    public ResponseEntity<List<NewsArticle>> getNews(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "1") int daysBack) {
        return ResponseEntity.ok(newsAggregatorService.getNews(daysBack, limit));
    }

    /**
     * GET /api/cfo/news/personalized?limit=20&daysBack=1
     * News ranked by investor-specific personalized score.
     * Includes full scoring breakdown (portfolioBoost, correlationBoost, goalAlignment, etc.)
     */
    @GetMapping("/news/personalized")
    public ResponseEntity<PersonalizedNewsResponse> getPersonalizedNews(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "1") int daysBack) {
        return ResponseEntity.ok(
                personalizedRelevanceScorer.score(defaultUserId, daysBack, limit));
    }

    /**
     * POST /api/cfo/news/fetch
     * Trigger a manual news fetch.
     */
    @PostMapping("/news/fetch")
    public ResponseEntity<Map<String, Integer>> fetchNews() {
        int count = newsAggregatorService.fetchAndStoreNews();
        // Always trigger LLM refinement after a fetch (async — won't block the response)
        llmRefinementService.refineRecentArticles();
        return ResponseEntity.ok(Map.of("fetched", count));
    }

    /**
     * POST /api/cfo/news/review
     * Manually trigger LLM review of recent unreviewed articles.
     * Useful when Ollama was down during the last fetch cycle.
     */
    @PostMapping("/news/review")
    public ResponseEntity<Map<String, String>> triggerLlmReview() {
        llmRefinementService.refineRecentArticles();
        return ResponseEntity.ok(Map.of("status", "LLM review queued (async)"));
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/transactions?page=0&size=50
     * Unified transaction ledger (all sources).
     */
    @GetMapping("/transactions")
    public ResponseEntity<Page<Transaction>> getTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(cfoAdvisorService.getTransactions(page, size));
    }

    /**
     * GET /api/cfo/transactions/recent?days=30
     * Recent transactions for quick review.
     */
    @GetMapping("/transactions/recent")
    public ResponseEntity<List<Transaction>> getRecentTransactions(
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(cfoAdvisorService.getRecentTransactions(days));
    }

    // ── Goal Advice ───────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/goals/advice
     * AI advice on current goals.
     */
    @GetMapping("/goals/advice")
    public ResponseEntity<AiInsight> getGoalAdvice() {
        AiInsight advice = cfoAdvisorService.generateGoalAdvice();
        if (advice == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(advice);
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    /**
     * POST /api/cfo/chat
     * Ask your CFO anything (stateless — each request gets full context injected).
     * Body: { "message": "Should I buy more HDFC Bank?" }
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String response = cfoAdvisorService.chat(List.of(), request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    record ChatRequest(String message) {}
    record ChatResponse(String response) {}

    // ── Price Data ────────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/prices/providers
     * Returns the active price provider fallback chain.
     */
    @GetMapping("/prices/providers")
    public ResponseEntity<Map<String, Object>> getPriceProviders() {
        return ResponseEntity.ok(Map.of(
                "providerChain", stockPriceService.getProviderChain(),
                "description", "Providers are tried in order; first success wins"
        ));
    }

    /**
     * POST /api/cfo/prices/fetch
     * Manually trigger a price history fetch for all portfolio symbols.
     */
    @PostMapping("/prices/fetch")
    public ResponseEntity<Map<String, String>> fetchPrices() {
        stockPriceService.fetchAndPersistPrices(defaultUserId);
        return ResponseEntity.ok(Map.of("status", "Price fetch completed"));
    }

    /**
     * POST /api/cfo/prices/fetch/{symbol}
     * Fetch price history for a specific symbol (useful for testing individual providers).
     */
    @PostMapping("/prices/fetch/{symbol}")
    public ResponseEntity<Map<String, Object>> fetchPriceForSymbol(@PathVariable String symbol) {
        try {
            int saved = stockPriceService.fetchAndPersistSymbol(symbol.toUpperCase());
            return ResponseEntity.ok(Map.of(
                    "symbol", symbol.toUpperCase(),
                    "newRecordsSaved", saved,
                    "providerChain", stockPriceService.getProviderChain()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of(
                    "symbol", symbol.toUpperCase(),
                    "error", e.getMessage(),
                    "providerChain", stockPriceService.getProviderChain()
            ));
        }
    }

    // ── Investor Profile: Behavior + Questionnaire ───────────────────────────

    /**
     * GET /api/cfo/investor/behavior
     * Returns the derived behavioral profile (trading style, panic ratio, win rate, etc.)
     */
    @GetMapping("/investor/behavior")
    public ResponseEntity<InvestorBehaviorProfile> getBehaviorProfile() {
        return investorBehaviorService.getProfile(defaultUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/cfo/investor/behavior/refresh
     * Force-recomputes the behavioral profile from full transaction history.
     */
    @PostMapping("/investor/behavior/refresh")
    public ResponseEntity<InvestorBehaviorProfile> refreshBehaviorProfile() {
        return ResponseEntity.ok(investorBehaviorService.recompute(defaultUserId));
    }

    /**
     * GET /api/cfo/investor/questionnaire
     * Returns the stored risk questionnaire answers.
     */
    @GetMapping("/investor/questionnaire")
    public ResponseEntity<RiskQuestionnaire> getQuestionnaire() {
        return riskQuestionnaireRepository.findByUserId(defaultUserId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /api/cfo/investor/questionnaire
     * Submit or update risk questionnaire answers.
     * Automatically computes statedRiskScore and updates hypocrisyScore.
     */
    @PutMapping("/investor/questionnaire")
    public ResponseEntity<RiskQuestionnaire> updateQuestionnaire(
            @RequestBody RiskQuestionnaire request) {
        request.setUserId(defaultUserId);
        request.setStatedRiskScore(investorBehaviorService.scoreQuestionnaire(request));
        request.setCompletedAt(java.time.LocalDateTime.now());
        RiskQuestionnaire saved = riskQuestionnaireRepository.save(request);
        // Refresh behavior profile so hypocrisyScore is recomputed
        investorBehaviorService.recomputeAsync(defaultUserId);
        return ResponseEntity.ok(saved);
    }

    // ── Auth / Groww Token ────────────────────────────────────────────────────

    /**
     * PUT /api/cfo/auth/groww/token
     * Update Groww Bearer token manually (paste from browser DevTools).
     * Body: { "token": "eyJhbG..." }
     */
    @PutMapping("/auth/groww/token")
    public ResponseEntity<Map<String, String>> updateGrowwToken(@RequestBody TokenRequest request) {
        growwAuthService.saveToken(defaultUserId, request.token());
        return ResponseEntity.ok(Map.of("status", "Token saved successfully"));
    }

    /**
     * POST /api/cfo/auth/groww/credentials
     * Store Groww credentials for auto-refresh (JSON: {username, password}).
     * Body: { "credentialsJson": "{\"email\":\"...\",\"password\":\"...\"}" }
     */
    @PostMapping("/auth/groww/credentials")
    public ResponseEntity<Map<String, String>> saveGrowwCredentials(@RequestBody CredentialsRequest request) {
        growwAuthService.saveCredentials(defaultUserId, request.credentialsJson());
        return ResponseEntity.ok(Map.of("status", "Credentials saved for auto-refresh"));
    }

    record TokenRequest(String token) {}
    record CredentialsRequest(String credentialsJson) {}

    // ── User Profile ──────────────────────────────────────────────────────────

    /**
     * GET /api/cfo/profile
     */
    @GetMapping("/profile")
    public ResponseEntity<UserProfile> getProfile() {
        return cfoAdvisorService.getProfile()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * PUT /api/cfo/profile
     * Update user profile (income, risk appetite, goals context, etc.)
     */
    @PutMapping("/profile")
    public ResponseEntity<UserProfile> updateProfile(@RequestBody UserProfileRequest request) {
        UserProfile profile = cfoAdvisorService.updateProfile(
                request.name(), request.email(), request.monthlyIncome(),
                request.monthlyFixedExpenses(), request.riskAppetite(),
                request.investmentHorizonYears(), request.targetMonthlySavings(),
                request.primaryGoalDescription(), request.additionalContext());
        return ResponseEntity.ok(profile);
    }

    record UserProfileRequest(
            String name,
            String email,
            BigDecimal monthlyIncome,
            BigDecimal monthlyFixedExpenses,
            UserProfile.RiskAppetite riskAppetite,
            Integer investmentHorizonYears,
            BigDecimal targetMonthlySavings,
            String primaryGoalDescription,
            String additionalContext
    ) {}
}
