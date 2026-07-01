package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.dto.InsightResponse;
import org.amit.finwise.cfo.dto.TransactionResponse;
import org.amit.finwise.cfo.model.*;
import org.amit.finwise.cfo.repository.RiskQuestionnaireRepository;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.amit.finwise.cfo.service.GrowwAuthService;
import org.amit.finwise.cfo.service.InvestorBehaviorService;
import org.amit.finwise.cfo.service.PersonalizedRelevanceScorer;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.LookThroughService;
import org.amit.finwise.cfo.service.ingestion.GrowwConnector;
import org.amit.finwise.cfo.service.ingestion.MfPortfolioImportService;
import org.amit.finwise.cfo.service.ingestion.NewsAggregatorService;
import org.amit.finwise.cfo.service.llm.LlmRefinementService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    private final InvestmentRepository investmentRepository;
    private final MfPortfolioImportService mfPortfolioImportService;
    private final LookThroughService lookThroughService;
    private final AttributionService attributionService;
    private final org.amit.finwise.cfo.service.insight.InsightCardService insightCardService;
    private final org.amit.finwise.cfo.service.fiduciary.FiduciaryWrapper fiduciaryWrapper;
    private final org.amit.finwise.cfo.service.insight.ConfidenceCalibrationService confidenceCalibrationService;
    private final org.amit.finwise.cfo.service.fiduciary.AuditTrailService auditTrailService;

    // ── Daily Brief ────────────────────────────────────────────────────────────

    @GetMapping("/brief")
    public ResponseEntity<InsightResponse> getDailyBrief(@AuthenticationPrincipal UserDetails principal) {
        AiInsight brief = cfoAdvisorService.generateDailyBrief(principal.getUsername());
        return ResponseEntity.ok(InsightResponse.from(brief));
    }

    // ── Insights ──────────────────────────────────────────────────────────────

    @GetMapping("/insights")
    public ResponseEntity<Page<InsightResponse>> getInsights(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(cfoAdvisorService.getInsights(principal.getUsername(), page, size)
                .map(InsightResponse::from));
    }

    @GetMapping("/insights/{type}")
    public ResponseEntity<InsightResponse> getInsightByType(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable AiInsight.InsightType type) {
        return cfoAdvisorService.getLatestInsightByType(principal.getUsername(), type)
                .map(InsightResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Portfolio ─────────────────────────────────────────────────────────────

    @GetMapping("/portfolio")
    public ResponseEntity<PortfolioSnapshot> getLatestPortfolio(
            @AuthenticationPrincipal UserDetails principal) {
        return cfoAdvisorService.getLatestPortfolioSnapshot(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/sync/groww")
    public ResponseEntity<PortfolioSnapshot> syncGroww(
            @AuthenticationPrincipal UserDetails principal) {
        PortfolioSnapshot snapshot = growwConnector.syncHoldings(principal.getUsername());
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/holdings")
    public ResponseEntity<List<HoldingSummary>> getHoldings(
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal.getUsername();
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return ResponseEntity.ok(List.of());

        double totalCost = investments.stream()
                .filter(i -> i.getTotalCost() != null)
                .mapToDouble(i -> i.getTotalCost().doubleValue())
                .sum();

        List<HoldingSummary> summaries = investments.stream().map(inv -> {
            double exposure = (totalCost > 0 && inv.getTotalCost() != null)
                    ? (inv.getTotalCost().doubleValue() / totalCost) * 100 : 0;
            return new HoldingSummary(
                    inv.getSymbol(),
                    inv.getName(),
                    inv.getSector(),
                    inv.getType() != null ? inv.getType().name() : null,
                    inv.getQuantity(),
                    inv.getCostPerUnit(),
                    inv.getTotalCost(),
                    inv.getCurrentPrice(),
                    inv.getCurrentValue(),
                    inv.getUnrealizedGainLoss(),
                    inv.getGainLossPercentage(),
                    Math.round(exposure * 100.0) / 100.0,
                    inv.getPlatform()
            );
        }).toList();

        return ResponseEntity.ok(summaries);
    }

    record HoldingSummary(
            String symbol,
            String name,
            String sector,
            String type,
            java.math.BigDecimal quantity,
            java.math.BigDecimal avgPrice,
            java.math.BigDecimal totalCost,
            java.math.BigDecimal currentPrice,
            java.math.BigDecimal currentValue,
            java.math.BigDecimal unrealizedPnl,
            java.math.BigDecimal pnlPercent,
            double exposurePercent,
            String platform
    ) {}


    // ── Mutual-Fund Look-Through & Attribution ────────────────────────────────

    @PostMapping(value = "/mf-portfolio/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MfPortfolioImportService.ImportSummary> importMfPortfolio(
            @RequestParam("file") MultipartFile file) {
        try {
            String csv = new String(file.getBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(mfPortfolioImportService.importCsv(csv));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read MF portfolio CSV", e);
        }
    }

    @GetMapping("/look-through")
    public ResponseEntity<LookThroughResult> getLookThrough(
            @AuthenticationPrincipal UserDetails principal) {
        return lookThroughService.compute(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/attribution")
    public ResponseEntity<AttributionReport> getAttribution(
            @AuthenticationPrincipal UserDetails principal) {
        return attributionService.compute(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/insight-cards")
    public ResponseEntity<org.amit.finwise.cfo.service.fiduciary.FiduciaryEnvelope<java.util.List<org.amit.finwise.cfo.model.InsightCard>>> getInsightCards(
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal.getUsername();
        java.util.List<org.amit.finwise.cfo.model.InsightCard> cards = insightCardService.generate(userId);
        return ResponseEntity.ok(fiduciaryWrapper.wrap(
                cards,
                List.of("NSE-bhavcopy", "AMFI", "Yahoo-Finance"),
                "EOD prices; portfolio valued at last close",
                buildConfidenceSummary()
        ));
    }

    private String buildConfidenceSummary() {
        try {
            return confidenceCalibrationService.trackRecord(null, null);
        } catch (Exception e) {
            return "calibration unavailable";
        }
    }

    @GetMapping("/insight-cards/marginal-add")
    public ResponseEntity<org.amit.finwise.cfo.model.InsightCard> getMarginalAddCard(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String symbol) {
        return insightCardService.marginalAddCard(principal.getUsername(), symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/audit")
    public ResponseEntity<org.amit.finwise.cfo.service.fiduciary.FiduciaryEnvelope<List<RecommendationAudit>>> auditTrail(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "90") int days) {
        String userId = principal.getUsername();
        java.time.LocalDateTime from = java.time.LocalDateTime.now().minusDays(days);
        List<RecommendationAudit> trail = auditTrailService.findByUser(userId, from);
        return ResponseEntity.ok(fiduciaryWrapper.wrap(trail, List.of(), null,
                trail.size() + " recommendations in last " + days + " days"));
    }

    // ── News ──────────────────────────────────────────────────────────────────

    @GetMapping("/news")
    public ResponseEntity<List<NewsArticle>> getNews(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "1") int daysBack) {
        return ResponseEntity.ok(newsAggregatorService.getNews(daysBack, limit));
    }

    @GetMapping("/news/personalized")
    public ResponseEntity<PersonalizedNewsResponse> getPersonalizedNews(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "1") int daysBack) {
        return ResponseEntity.ok(
                personalizedRelevanceScorer.score(principal.getUsername(), daysBack, limit));
    }

    @PostMapping("/news/fetch")
    public ResponseEntity<Map<String, Integer>> fetchNews() {
        int count = newsAggregatorService.fetchAndStoreNews();
        llmRefinementService.refineRecentArticles();
        return ResponseEntity.ok(Map.of("fetched", count));
    }

    @PostMapping("/news/review")
    public ResponseEntity<Map<String, String>> triggerLlmReview() {
        llmRefinementService.refineRecentArticles();
        return ResponseEntity.ok(Map.of("status", "LLM review queued (async)"));
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    @GetMapping("/transactions")
    public ResponseEntity<Page<TransactionResponse>> getTransactions(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(cfoAdvisorService.getTransactions(principal.getUsername(), page, size)
                .map(TransactionResponse::from));
    }

    @GetMapping("/transactions/recent")
    public ResponseEntity<List<TransactionResponse>> getRecentTransactions(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(cfoAdvisorService.getRecentTransactions(principal.getUsername(), days)
                .stream().map(TransactionResponse::from).toList());
    }

    // ── Goal Advice ───────────────────────────────────────────────────────────

    @GetMapping("/goals/advice")
    public ResponseEntity<AiInsight> getGoalAdvice(
            @AuthenticationPrincipal UserDetails principal) {
        AiInsight advice = cfoAdvisorService.generateGoalAdvice(principal.getUsername());
        if (advice == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(advice);
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody ChatRequest request) {
        String response = cfoAdvisorService.chat(List.of(), principal.getUsername(), request.message());
        return ResponseEntity.ok(new ChatResponse(response));
    }

    record ChatRequest(String message) {}
    record ChatResponse(String response) {}

    // ── Price Data ────────────────────────────────────────────────────────────

    @GetMapping("/prices/providers")
    public ResponseEntity<Map<String, Object>> getPriceProviders() {
        return ResponseEntity.ok(Map.of(
                "providerChain", stockPriceService.getProviderChain(),
                "description", "Providers are tried in order; first success wins"
        ));
    }

    @PostMapping("/prices/fetch")
    public ResponseEntity<Map<String, String>> fetchPrices(
            @AuthenticationPrincipal UserDetails principal) {
        stockPriceService.fetchAndPersistPrices(principal.getUsername());
        return ResponseEntity.ok(Map.of("status", "Price fetch completed"));
    }

    @PostMapping("/prices/fetch-symbol")
    public ResponseEntity<Map<String, Object>> fetchPriceForSymbol(@RequestParam String symbol) {
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

    @PostMapping("/prices/sync")
    public ResponseEntity<Map<String, String>> syncPricesToPortfolio(
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal.getUsername();
        stockPriceService.updateInvestmentCurrentPrices(userId);
        stockPriceService.rebuildPortfolioSnapshot(userId);
        return ResponseEntity.ok(Map.of("status", "Investment prices and portfolio snapshot updated"));
    }

    // ── Investor Profile: Behavior + Questionnaire ───────────────────────────

    @GetMapping("/investor/behavior")
    public ResponseEntity<InvestorBehaviorProfile> getBehaviorProfile(
            @AuthenticationPrincipal UserDetails principal) {
        return investorBehaviorService.getProfile(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/investor/behavior/refresh")
    public ResponseEntity<InvestorBehaviorProfile> refreshBehaviorProfile(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(investorBehaviorService.recompute(principal.getUsername()));
    }

    @GetMapping("/investor/questionnaire")
    public ResponseEntity<RiskQuestionnaire> getQuestionnaire(
            @AuthenticationPrincipal UserDetails principal) {
        return riskQuestionnaireRepository.findByUserId(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/investor/questionnaire")
    public ResponseEntity<RiskQuestionnaire> updateQuestionnaire(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody RiskQuestionnaire request) {
        String userId = principal.getUsername();
        request.setUserId(userId);
        request.setStatedRiskScore(investorBehaviorService.scoreQuestionnaire(request));
        request.setCompletedAt(java.time.LocalDateTime.now());
        RiskQuestionnaire saved = riskQuestionnaireRepository.save(request);
        investorBehaviorService.recomputeAsync(userId);
        return ResponseEntity.ok(saved);
    }

    // ── Auth / Groww Token ────────────────────────────────────────────────────

    @PutMapping("/auth/groww/token")
    public ResponseEntity<Map<String, String>> updateGrowwToken(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody TokenRequest request) {
        growwAuthService.saveToken(principal.getUsername(), request.token());
        return ResponseEntity.ok(Map.of("status", "Token saved successfully"));
    }

    @PostMapping("/auth/groww/credentials")
    public ResponseEntity<Map<String, String>> saveGrowwCredentials(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody CredentialsRequest request) {
        growwAuthService.saveCredentials(principal.getUsername(), request.credentialsJson());
        return ResponseEntity.ok(Map.of("status", "Credentials saved for auto-refresh"));
    }

    record TokenRequest(String token) {}
    record CredentialsRequest(String credentialsJson) {}

    // ── User Profile ──────────────────────────────────────────────────────────

    @GetMapping("/profile")
    public ResponseEntity<UserProfile> getProfile(
            @AuthenticationPrincipal UserDetails principal) {
        return cfoAdvisorService.getProfile(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/profile")
    public ResponseEntity<UserProfile> updateProfile(
            @AuthenticationPrincipal UserDetails principal,
            @RequestBody UserProfileRequest request) {
        UserProfile profile = cfoAdvisorService.updateProfile(
                principal.getUsername(),
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
