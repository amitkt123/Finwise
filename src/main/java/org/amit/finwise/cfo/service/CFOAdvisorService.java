package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.NewsArticle;
import org.amit.finwise.cfo.model.PersonalizedNewsItem;
import org.amit.finwise.cfo.model.PersonalizedNewsResponse;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.model.UserProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.amit.finwise.cfo.repository.AiInsightRepository;
import org.amit.finwise.cfo.repository.NewsArticleRepository;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.repository.UserProfileRepository;
import org.amit.finwise.cfo.model.FactorRiskReport;
import org.amit.finwise.cfo.model.RiskDecomposition;
import org.amit.finwise.cfo.model.StockDeepDive;
import org.amit.finwise.cfo.model.StockScorecard;
import org.amit.finwise.cfo.model.TechnicalSnapshot;
import org.amit.finwise.cfo.service.analytics.FactorModelService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.cfo.service.analytics.TechnicalAnalysisService;
import org.amit.finwise.cfo.service.llm.LLMMessage;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.cfo.service.research.IntentClassifier;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CFOAdvisorService {

    private final LLMProvider llmProvider;
    private final UserProfileRepository userProfileRepository;
    private final PortfolioSnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;
    private final NewsArticleRepository newsArticleRepository;
    private final FinancialGoalRepository goalRepository;
    private final AiInsightRepository insightRepository;
    private final InvestorBehaviorService behaviorService;
    private final PersonalizedRelevanceScorer personalizedRelevanceScorer;
    private final MarketContextService marketContextService;
    private final InvestmentRepository investmentRepository;
    private final StockPriceHistoryRepository stockPriceHistoryRepository;
    private final PolicyIntelligenceService policyIntelligenceService;
    private final PortfolioRiskService portfolioRiskService;
    private final FactorModelService factorModelService;
    private final PortfolioPerformanceService portfolioPerformanceService;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final IntentClassifier intentClassifier;
    private final StockIntelligenceService stockIntelligenceService;
    private final org.amit.finwise.investment.service.TaxHarvestingService taxHarvestingService;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    @Value("${cfo.user.name:User}")
    private String userName;

    // ── System Prompt ─────────────────────────────────────────────────────────

    private static final String CFO_SYSTEM_PROMPT = """
            You are a highly experienced Personal CFO (Chief Financial Officer) and financial advisor for an Indian retail investor.
            Your role is to provide clear, actionable, data-driven financial guidance.

            Guidelines:
            - Be concise, specific, and actionable. No generic advice.
            - All amounts are in Indian Rupees (INR / ₹).
            - Reference specific stocks, funds, or numbers from the context provided.
            - Consider Indian market context: NSE/BSE, SEBI regulations, RBI policies, Indian tax implications (LTCG/STCG).
            - Align advice with the user's risk appetite and financial goals.
            - If market news is negative for holdings, flag it clearly with suggested action.
            - Treat official policy and regulatory sources as higher-trust evidence than media commentary.
            - If policy evidence is provided, reason from the policy event card: what changed, effective date, affected party, transmission channel, binding level, and surprise.
            - Do not collapse policy impact to one net sign when channels conflict; say MIXED and name the drivers.
            - Cite the authority, reference, and effective date where possible.
            - Format responses in clean Markdown with sections.
            - Do not make up numbers — only use figures from the provided context.
            - Before recommending, list what data you would want but do not have. If a recommendation depends on missing data, label it LOW-CONFIDENCE and state why.
            - Confidence scores must cite which specific evidence (e.g. which metric, news item, or policy card) drives them.
            - If a field shows DATA_INCOMPLETE or INSUFFICIENT_HISTORY, declare the gap explicitly — do NOT substitute training-data recall for missing live data.
            - All risk numbers (volatility, VaR, beta) in context are computed from actual price history — treat them as authoritative, not approximate.
            """;

    private static final String RESEARCH_SYSTEM_PROMPT = """
            ## Research Mode Instructions
            You are analyzing a specific stock at the user's request. Follow this structure exactly:

            1. **Quick Verdict**: use the Scorecard Recommendation exactly: BUY / WAIT / AVOID / NEEDS_MORE_DATA
            2. **Scorecard First**: cite total score, confidence, and component scores before narrative thesis
            3. **Data Evidence**: cite specific computed evidence from the ## Stock Scorecard and ## Stock Deep-Dive blocks
            4. **Thesis**: 2-3 sentences anchored to the evidence pack, NOT general knowledge
            5. **Break-Condition**: one falsifiable condition that would reverse the verdict
            6. **Fit-to-Portfolio**: use the Portfolio Fit section — state how this position changes portfolio risk
            7. **Concrete Action**: specific entry condition, suggested position size vs portfolio, and one key watch level

            Rules:
            - Never override the Java-computed Scorecard Recommendation.
            - If confidence < 50, refuse a hard buy/wait/avoid call and explain missing data.
            - Every claim must cite a specific metric from the context (e.g. "valuationScore=72", "RSI=72 → overbought", "β=1.4 → amplifies Nifty moves")
            - If a field shows DATA_INCOMPLETE or INSUFFICIENT_HISTORY, declare the gap. Do NOT use training-data recall to fill missing live data.
            - Confidence on each recommendation must cite which data point drives it (0.0–1.0)
            - If hasPriceHistory=false, respond with NEEDS-MORE-DATA and explain why no live data is available
            """;

    // ── Daily Brief ───────────────────────────────────────────────────────────

    private static final int BRIEF_COOLDOWN_MINUTES = 120;

    /**
     * Generate the daily CFO morning brief.
     * Regenerates if no brief exists today, or if the last one is older than BRIEF_COOLDOWN_MINUTES.
     * This ensures a fresh brief is produced after each Groww sync + price fetch cycle,
     * rather than serving a stale 7:30 AM brief all day.
     */
    public AiInsight generateDailyBrief() {
        String userId = defaultUserId;
        LocalDate today = LocalDate.now();

        Optional<AiInsight> existing = insightRepository.findFirstByUserIdAndInsightDateAndInsightTypeOrderByCreatedAtDesc(
                userId, today, AiInsight.InsightType.DAILY_BRIEF);
        if (existing.isPresent()) {
            boolean fresh = existing.get().getCreatedAt()
                    .isAfter(LocalDateTime.now().minusMinutes(BRIEF_COOLDOWN_MINUTES));
            if (fresh) {
                log.debug("Daily brief is fresh (generated {}), skipping regeneration",
                        existing.get().getCreatedAt());
                return existing.get();
            }
            log.info("Daily brief is stale (generated {}), regenerating with latest data",
                    existing.get().getCreatedAt());
        }

        String context = buildDailyBriefContext(userId);
        String userPrompt = """
                Generate my daily CFO morning brief for %s.
                Include:
                1. **Portfolio Summary** — Key P&L metrics, day change, top holdings by exposure%%
                2. **Market & News Highlights** — Top 3-5 relevant stories with SPECIFIC impact on my holdings (use exposure %% from context)
                3. **Goal Progress** — Quick status of my active financial goals
                4. **Action Items by Time Horizon**:
                   - ⏳ Short-Term (0–7 days): Immediate actions
                   - 📅 Medium-Term (1–3 months): Positioning decisions
                   - 🧱 Long-Term (1+ year): Strategic allocation shifts
                   For each action include: Confidence: X.X (0.0–1.0)
                5. **Risk Assessment** — Lead with the quant **Risk Decomposition** block (annualized vol, portfolio beta vs Nifty, 1-day VaR/CVaR, effective bets, top risk contributors with %% of volatility). Map the top contributors to specific holdings. Treat the **News-Sentiment Risk Scorecard** as a secondary sentiment signal only — never present it as the portfolio's actual risk.

                Rules:
                - Reference exact holdings and exposure %% from context. Never say "no portfolio data".
                - For each news → holding impact, state: "[Stock] has X%% exposure, [POSITIVE/NEGATIVE] impact due to [reason]"
                - Anchor every risk statement to a number from the Risk Decomposition block; if it shows LOW_CONFIDENCE or is absent, say so rather than substituting the sentiment score.
                - Open with a CAUTION banner if the Risk Decomposition shows portfolio beta > 1.2, is flagged LOW_CONFIDENCE, or the News-Sentiment Risk Score ≥ 70.

                Context:
                %s
                """.formatted(today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), context);

        String content = llmProvider.chat(CFO_SYSTEM_PROMPT, userPrompt);

        AiInsight insight = AiInsight.builder()
                .userId(userId)
                .insightDate(today)
                .insightType(AiInsight.InsightType.DAILY_BRIEF)
                .title("Daily CFO Brief - " + today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
                .content(content)
                .modelUsed(llmProvider.providerName())
                .build();

        return insightRepository.save(insight);
    }

    // ── After-Hours Insights ──────────────────────────────────────────────────

    public AiInsight generateAfterHoursInsights() {
        String userId = defaultUserId;
        LocalDate today = LocalDate.now();

        String context = buildAfterHoursContext(userId);
        String userPrompt = """
                Generate an after-hours portfolio review and insights for today (%s).
                Include:
                1. **Day's Performance** — Portfolio P&L vs Nifty 50; reference exact holdings from context
                2. **News → Holdings Impact Table** — For each relevant news item, map it to a specific holding:
                   | Holding | Sector | Exposure%% | News Catalyst | Impact | Confidence |
                3. **Opportunities Spotted** — Buy/sell signals with Confidence: X.X (0.0–1.0)
                4. **Rebalancing Check** — Use sector exposure from context; flag any sector > 35%% as concentrated
                5. **Tomorrow's Watch** — Key events segmented by time horizon:
                   - ⏳ 0–7 days | 📅 1–3 months

                Rules:
                - Always reference specific holdings and their exposure %% from context
                - Every recommendation must include a Confidence score

                Context:
                %s
                """.formatted(today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), context);

        String content = llmProvider.chat(CFO_SYSTEM_PROMPT, userPrompt);

        AiInsight insight = AiInsight.builder()
                .userId(userId)
                .insightDate(today)
                .insightType(AiInsight.InsightType.MARKET_INSIGHT)
                .title("After-Hours Review - " + today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
                .content(content)
                .modelUsed(llmProvider.providerName())
                .build();

        return insightRepository.save(insight);
    }

    // ── Mid-Day / Post-Close Market Insight ──────────────────────────────────

    /**
     * Generate a MARKET_INSIGHT snapshot — called twice by the scheduler:
     *   (a) 12:30 PM after mid-session Groww sync  → label = "Mid-Day"
     *   (b) 4:30 PM after stock price fetch finishes → label = "Post-Close"
     *
     * Unlike generateDailyBrief(), there is no cooldown guard — the scheduler
     * controls call frequency and every call should produce a fresh record so
     * the UI can compare them.
     *
     * @param label short label embedded in the title, e.g. "Mid-Day" or "Post-Close"
     */
    public AiInsight generateMarketInsight(String label) {
        String userId = defaultUserId;
        LocalDate today = LocalDate.now();

        String context = buildAfterHoursContext(userId);
        String userPrompt = """
                Generate a %s market insight for %s.
                Include:
                1. **Portfolio Snapshot** — Current value, unrealized P&L, day change vs invested cost
                2. **Price Movers** — Top gainers and losers from my holdings today (use price trend data from context)
                3. **News → Holdings Impact** — For each relevant news item map it to a holding:
                   | Holding | Sector | Exposure%% | News Catalyst | Impact | Confidence |
                4. **Intraday Observations** — Any circuit breaker hits, unusual volume, or anomalies
                5. **Actionable Signals** — Concrete buy/hold/sell signals with Confidence: X.X (0.0–1.0)

                Rules:
                - Use exact prices and %% changes from the "Recent Price Trends" section in context.
                - Reference holdings by symbol and exposure %%.
                - If no circuit breakers or anomalies — explicitly state "No anomalies detected today."

                Context:
                %s
                """.formatted(label, today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")), context);

        String content = llmProvider.chat(CFO_SYSTEM_PROMPT, userPrompt);

        AiInsight insight = AiInsight.builder()
                .userId(userId)
                .insightDate(today)
                .insightType(AiInsight.InsightType.MARKET_INSIGHT)
                .title(label + " Market Insight - " + today.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
                .content(content)
                .modelUsed(llmProvider.providerName())
                .build();

        return insightRepository.save(insight);
    }

    // ── Goal Advice ────────────────────────────────────────────────────────────

    public AiInsight generateGoalAdvice() {
        String userId = defaultUserId;

        List<FinancialGoal> goals = goalRepository.findActiveGoals(userId);
        if (goals.isEmpty()) {
            log.info("No active goals found for user {}", userId);
            return null;
        }

        UserProfile profile = userProfileRepository.findByUserId(userId).orElse(null);
        PortfolioSnapshot snapshot = snapshotRepository
                .findTopByUserIdOrderBySnapshotTimeDesc(userId).orElse(null);

        String goalsContext = goals.stream().map(g ->
                "- %s (Target: ₹%s by %s, Progress: %s%%, Status: %s)".formatted(
                        g.getName(), g.getTargetAmount(), g.getTargetDate(),
                        g.getProgressPercentage(), g.getStatus())
        ).collect(Collectors.joining("\n"));

        String portfolioContext = snapshot != null
                ? "Portfolio: ₹%s current value, ₹%s invested, P&L: ₹%s (%s%%)".formatted(
                        snapshot.getCurrentValue(), snapshot.getTotalInvested(),
                        snapshot.getUnrealizedPnl(), snapshot.getOverallPnlPercent())
                : "Portfolio data not available";

        String profileContext = profile != null
                ? "Monthly income: ₹%s, Risk appetite: %s, Investment horizon: %d years".formatted(
                        profile.getMonthlyIncome(), profile.getRiskAppetite(), profile.getInvestmentHorizonYears())
                : "";

        String userPrompt = """
                Review my financial goals and provide specific advice on how to achieve them faster or get back on track.

                Goals:
                %s

                %s
                %s

                For each at-risk or off-track goal:
                - Explain why it's at risk
                - Give a concrete monthly savings/investment amount
                - Suggest which investment vehicle is best suited for this goal
                - Give a recovery plan
                """.formatted(goalsContext, portfolioContext, profileContext);

        String content = llmProvider.chat(CFO_SYSTEM_PROMPT, userPrompt);

        AiInsight insight = AiInsight.builder()
                .userId(userId)
                .insightDate(LocalDate.now())
                .insightType(AiInsight.InsightType.GOAL_ADVICE)
                .title("Goal Review & Advice")
                .content(content)
                .modelUsed(llmProvider.providerName())
                .build();

        return insightRepository.save(insight);
    }

    // ── Conversational Chat ────────────────────────────────────────────────────

    public String chat(List<LLMMessage> conversationHistory, String userMessage) {
        IntentClassifier.ClassifiedIntent intent = intentClassifier.classify(userMessage);
        log.info("[CFO.chat] intent={} symbol={} confidence={}",
                intent.type(), intent.symbol(), intent.confidence());

        String systemPrompt;
        String contextBlock;

        if (intent.type() == IntentClassifier.Intent.RESEARCH && intent.symbol() != null) {
            // RESEARCH path: deep-dive first (primacy), portfolio as fit-context after
            StockDeepDive deepDive = stockIntelligenceService.analyze(intent.symbol(), defaultUserId);
            contextBlock = buildResearchContext(deepDive, defaultUserId, userMessage);
            systemPrompt = CFO_SYSTEM_PROMPT + "\n\n" + RESEARCH_SYSTEM_PROMPT;
        } else {
            // PORTFOLIO_REVIEW / PLANNING: existing context layout
            contextBlock = buildFullContext(defaultUserId, userMessage);
            systemPrompt = CFO_SYSTEM_PROMPT;
        }

        List<LLMMessage> messages;
        if (conversationHistory.isEmpty()) {
            // Phase 6: split system messages so static prompt is cached separately
            messages = List.of(
                    LLMMessage.cachedSystem(systemPrompt),
                    LLMMessage.system("\n\n## Your Current Financial Context:\n" + contextBlock),
                    LLMMessage.user(userMessage)
            );
        } else {
            messages = new java.util.ArrayList<>(conversationHistory);
            messages.add(LLMMessage.user(userMessage));
        }

        String response = llmProvider.chatWithHistory(messages);

        AiInsight insight = AiInsight.builder()
                .userId(defaultUserId)
                .insightDate(LocalDate.now())
                .insightType(AiInsight.InsightType.CHAT_RESPONSE)
                .title("CFO Chat: " + userMessage.substring(0, Math.min(60, userMessage.length())))
                .content(response)
                .modelUsed(llmProvider.providerName())
                .build();
        insightRepository.save(insight);

        return response;
    }

    /**
     * Builds the context block for a RESEARCH-intent query.
     * Deep-dive leads (primacy), portfolio follows as fit-context.
     */
    private String buildResearchContext(StockDeepDive deepDive, String userId, String userMessage) {
        StringBuilder ctx = new StringBuilder();

        // ── Deep-dive first (primacy for RESEARCH queries) ────────────────────
        ctx.append("## Stock Deep-Dive: ").append(deepDive.symbol()).append("\n");
        ctx.append("As of: ").append(deepDive.asOf()).append("\n");

        // Data quality gate
        if (!deepDive.knownSymbol()) {
            ctx.append("⚠ UNKNOWN_SYMBOL: ").append(deepDive.symbol())
               .append(" not found in the stock gazetteer.\n");
        }
        if (!deepDive.hasPriceHistory()) {
            ctx.append("⚠ DATA_INCOMPLETE:PRICE_HISTORY — no live price data available for ")
               .append(deepDive.symbol()).append(". Do NOT use training-data recall.\n");
        }
        if (!deepDive.dataGaps().isEmpty()) {
            ctx.append("Data Gaps: ").append(String.join("; ", deepDive.dataGaps())).append("\n");
        }
        ctx.append("\n");

        // Scorecard before narrative inputs: this is the authoritative recommendation.
        StockScorecard scorecard = deepDive.scorecard();
        if (scorecard != null) {
            ctx.append("## Stock Scorecard (computed, authoritative)\n");
            ctx.append(String.format("Recommendation: %s | Total Score: %.1f/100 | Confidence: %d/100 | Expected Return Band: %s%n",
                    scorecard.recommendation(), scorecard.totalScore(), scorecard.confidence(), scorecard.expectedReturnBand()));
            ctx.append(String.format("Components: valuation=%d, quality=%d, growth=%d, momentum=%d, risk=%d, newsMacro=%d, portfolioFit=%d%n",
                    scorecard.valuationScore(), scorecard.qualityScore(), scorecard.growthScore(),
                    scorecard.momentumScore(), scorecard.riskScore(), scorecard.newsMacroScore(),
                    scorecard.portfolioFitScore()));
            if (!scorecard.dataGaps().isEmpty()) {
                ctx.append("Scorecard Data Gaps: ").append(String.join("; ", scorecard.dataGaps())).append("\n");
            }
            ctx.append("Bull Case:\n");
            scorecard.bullCase().forEach(item -> ctx.append("- ").append(item).append("\n"));
            ctx.append("Bear Case:\n");
            scorecard.bearCase().forEach(item -> ctx.append("- ").append(item).append("\n"));
            ctx.append("Key Risks:\n");
            scorecard.keyRisks().forEach(item -> ctx.append("- ").append(item).append("\n"));
            ctx.append("Evidence Items:\n");
            scorecard.evidenceItems().stream().limit(12).forEach(item ->
                    ctx.append(String.format("- [%s] %s=%s | %s | direction=%s | confidence=%d | source=%s | asOf=%s%n",
                            item.category(), item.metricName(), item.value(), item.interpretation(),
                            item.direction(), item.confidence(), item.source(), item.asOf())));
            ctx.append("\n");
        }

        // Live quote
        if (deepDive.lastClose() != null) {
            ctx.append("### Live Quote\n");
            ctx.append(String.format("Last Close: ₹%.2f", deepDive.lastClose()));
            if (deepDive.dayChangePercent() != null)
                ctx.append(String.format("  |  Day Change: %+.2f%%", deepDive.dayChangePercent()));
            if (deepDive.hitCircuitToday())
                ctx.append("  |  ⚡ ").append(deepDive.circuitType()).append(" CIRCUIT TODAY");
            ctx.append("\n\n");
        }

        // Technical snapshot
        if (deepDive.technicals() != null) {
            ctx.append(deepDive.technicals().toContextBlock());
            ctx.append("\n");
        } else if (deepDive.hasPriceHistory()) {
            ctx.append("### Technicals\nDATA_INCOMPLETE:TECHNICALS — insufficient price history\n\n");
        }

        // Stock-specific news
        if (!deepDive.relevantNews().isEmpty()) {
            ctx.append("### Recent News (").append(deepDive.symbol()).append(")\n");
            deepDive.relevantNews().forEach(a -> {
                ctx.append("- [").append(a.getSentiment()).append("] ")
                   .append(a.getSource()).append(": ").append(a.getTitle());
                if (a.getSummary() != null && !a.getSummary().isBlank())
                    ctx.append(" — ").append(a.getSummary(), 0,
                            Math.min(120, a.getSummary().length()));
                ctx.append("\n");
            });
            ctx.append("\n");
        } else {
            ctx.append("### Recent News\nNo stock-specific news in the last 7 days.\n\n");
        }

        // Portfolio fit
        StockDeepDive.PortfolioFit fit = deepDive.portfolioFit();
        if (fit != null) {
            ctx.append("### Portfolio Fit\n");
            ctx.append(fit.fitSummary()).append("\n");
            if (fit.isHeld() && !Double.isNaN(fit.pctContributionToRisk()))
                ctx.append(String.format("Risk contribution: %.1f%% of portfolio volatility%n",
                        fit.pctContributionToRisk()));
            ctx.append("\n");

            // Phase 7: Quantitative fit metrics
            ctx.append("### Portfolio Fit (Quantitative)\n");
            if (!Double.isNaN(fit.correlationToPortfolio())) {
                String overlapLabel = fit.correlationToPortfolio() < 0.3 ? "LOW" :
                                     fit.correlationToPortfolio() < 0.7 ? "MODERATE" : "HIGH";
                ctx.append(String.format("Correlation to portfolio: %.2f (%s overlap)%n",
                        fit.correlationToPortfolio(), overlapLabel));
            } else {
                ctx.append("Correlation to portfolio: DATA_INCOMPLETE\n");
            }

            if (!Double.isNaN(fit.marginalVolImpact())) {
                String impact = fit.marginalVolImpact() < 0 ? "decreases" : "increases";
                ctx.append(String.format("Marginal vol impact at 5%% position: %+.2f%% p.a. (%s portfolio risk)%n",
                        fit.marginalVolImpact() * 100, impact));
                ctx.append(String.format("Projected portfolio vol (post-add): %.2f%% p.a.%n",
                        fit.newPortfolioVol() * 100));
            } else {
                ctx.append("Marginal vol impact: DATA_INCOMPLETE\n");
            }

            if (fit.verdictLabel() != null) {
                ctx.append(String.format("\n--- VERDICT: %s ---%n", fit.verdictLabel()));
                if (fit.verdictRationale() != null) {
                    ctx.append(String.format("Rationale: %s%n", fit.verdictRationale()));
                }
            }
            ctx.append("\n");
        }

        // ── Portfolio context follows as fit reference ─────────────────────────
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendQuantRiskDecomposition(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, userMessage, 4);
        appendTodaysNews(ctx, userId, 4);

        return ctx.toString();
    }

    // ── Context Builders ───────────────────────────────────────────────────────

    private String buildDailyBriefContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendQuantRiskDecomposition(ctx, userId);   // Phase 1: real portfolio risk math
        appendFactorRiskReport(ctx, userId);          // Phase 8: multi-factor decomposition
        appendNewsSentimentRisk(ctx, userId);         // news-based risk signal (renamed)
        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendRecentPriceTrends(ctx, userId, 5);
        appendSectorRiskMap(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, null, 6);
        appendActiveGoals(ctx, userId);
        appendTaxPlanning(ctx, userId);
        appendRecentTransactions(ctx, userId, 7);
        appendTodaysNews(ctx, userId, 10);

        return ctx.toString();
    }

    private String buildAfterHoursContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendQuantRiskDecomposition(ctx, userId);
        appendFactorRiskReport(ctx, userId);
        appendNewsSentimentRisk(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendRecentPriceTrends(ctx, userId, 5);
        appendSectorRiskMap(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, null, 6);
        appendAfternoonSnapshots(ctx, userId);
        appendTodaysNews(ctx, userId, 15);

        return ctx.toString();
    }

    private String buildFullContext(String userId, String userMessage) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendQuantRiskDecomposition(ctx, userId);
        appendFactorRiskReport(ctx, userId);
        appendNewsSentimentRisk(ctx, userId);
        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendRecentPriceTrends(ctx, userId, 5);
        appendSectorRiskMap(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, userMessage, 8);
        appendActiveGoals(ctx, userId);
        appendTaxPlanning(ctx, userId);
        appendRecentTransactions(ctx, userId, 30);
        appendTodaysNews(ctx, userId, 8);

        return ctx.toString();
    }

    private void appendUserProfile(StringBuilder ctx, String userId) {
        userProfileRepository.findByUserId(userId).ifPresent(p -> {
            ctx.append("## User Profile\n");
            ctx.append("Name: ").append(p.getName() != null ? p.getName() : userName).append("\n");
            if (p.getMonthlyIncome() != null) ctx.append("Monthly Income: ₹").append(p.getMonthlyIncome()).append("\n");
            if (p.getMonthlyFixedExpenses() != null) ctx.append("Fixed Expenses: ₹").append(p.getMonthlyFixedExpenses()).append("\n");
            ctx.append("Stated Risk Appetite: ").append(p.getRiskAppetite()).append("\n");
            ctx.append("Investment Horizon: ").append(p.getInvestmentHorizonYears()).append(" years\n");
            if (p.getPrimaryGoalDescription() != null) ctx.append("Primary Goal: ").append(p.getPrimaryGoalDescription()).append("\n");
            if (p.getAdditionalContext() != null) ctx.append("Additional Context: ").append(p.getAdditionalContext()).append("\n");
            ctx.append("\n");

            // ── Jarvis behavioral block (Phase 7) ─────────────────────────────
            behaviorService.getProfile(userId).ifPresent(b -> {
                ctx.append("## Investor Behavioral Profile (Derived from actual trading)\n");
                ctx.append("- Trading Style: ").append(b.getTradingStyle() != null ? b.getTradingStyle() : "UNKNOWN");
                if (b.getAvgHoldingDurationDays() != null && b.getAvgHoldingDurationDays() > 0)
                    ctx.append(" (avg hold: ").append(Math.round(b.getAvgHoldingDurationDays())).append(" days)");
                ctx.append("\n");
                ctx.append("- Derived Risk: ").append(b.getDerivedRiskAppetite() != null ? b.getDerivedRiskAppetite() : "UNKNOWN");
                ctx.append(" (stated: ").append(p.getRiskAppetite()).append(")");
                boolean highDivergence = b.getHypocrisyScore() != null && b.getHypocrisyScore() > 60;
                if (highDivergence) ctx.append(" — HIGH DIVERGENCE");
                ctx.append("\n");
                if (b.getPanicSellRatio() != null)
                    ctx.append("- Panic Sell Ratio: ").append(String.format("%.2f", b.getPanicSellRatio()))
                       .append(" (sells quickly on negative news)\n");
                if (b.getWinRate() != null)
                    ctx.append("- Win Rate: ").append(String.format("%.0f%%", b.getWinRate() * 100)).append("\n");
                if (b.getConcentrationHHI() != null)
                    ctx.append("- Concentration HHI: ").append(String.format("%.2f", b.getConcentrationHHI()))
                       .append(b.getConcentrationHHI() > 0.4 ? " (concentrated)" : " (diversified)").append("\n");
                if (b.getSectorAffinityJson() != null && !b.getSectorAffinityJson().equals("{}"))
                    ctx.append("- Sector Affinity (trade count): ").append(b.getSectorAffinityJson()).append("\n");
                if (b.getHypocrisyScore() != null)
                    ctx.append("- Behavioral Divergence Score: ").append(b.getHypocrisyScore()).append("/100\n");

                if (highDivergence) {
                    ctx.append("\n⚠ Behavioral Divergence Alert: This investor claims to be ")
                       .append(p.getRiskAppetite())
                       .append(" but behavior shows ")
                       .append(b.getDerivedRiskAppetite())
                       .append(" patterns. When drafting advice during market stress, adopt a ")
                       .append("'Voice of Reason' tone. Reference 30-day historical price volatility ")
                       .append("to contextualize dips. Do NOT recommend selling. Anchor to long-term goals.\n");
                }
                ctx.append("\n");
            });
        });
    }

    private void appendPortfolioSnapshot(StringBuilder ctx, String userId) {
        Optional<PortfolioSnapshot> snapshotOpt = snapshotRepository.findTopByUserIdOrderBySnapshotTimeDesc(userId);
        if (snapshotOpt.isPresent()) {
            PortfolioSnapshot s = snapshotOpt.get();
            ctx.append("## Portfolio (as of ").append(s.getSnapshotTime().format(DateTimeFormatter.ofPattern("dd MMM HH:mm"))).append(")\n");
            ctx.append("Total Invested: ₹").append(s.getTotalInvested()).append("\n");
            ctx.append("Current Value: ₹").append(s.getCurrentValue()).append("\n");
            ctx.append("Unrealized P&L: ₹").append(s.getUnrealizedPnl())
                    .append(" (").append(s.getOverallPnlPercent()).append("%)\n");
            if (s.getDayPnl() != null) {
                ctx.append("Day P&L: ₹").append(s.getDayPnl())
                        .append(" (").append(s.getDayPnlPercent()).append("%)\n");
            }
            ctx.append("Holdings Count: ").append(s.getHoldingsCount()).append("\n\n");
        } else {
            // No Groww-synced snapshot — fall back to aggregated totals directly from investments table
            BigDecimal totalCost = investmentRepository.totalInvestmentCost(userId);
            BigDecimal totalValue = investmentRepository.totalPortfolioValue(userId);
            BigDecimal unrealizedGains = investmentRepository.totalUnrealizedGains(userId);
            long holdingsCount = investmentRepository.findActiveInvestments(userId).size();

            if (holdingsCount > 0) {
                log.warn("No PortfolioSnapshot found for user {}. Falling back to investment table aggregates.", userId);
                ctx.append("## Portfolio (computed from investments — no Groww snapshot)\n");
                ctx.append("Total Invested: ₹").append(totalCost).append("\n");
                if (totalValue.compareTo(BigDecimal.ZERO) > 0) {
                    ctx.append("Current Value: ₹").append(totalValue).append("\n");
                    ctx.append("Unrealized P&L: ₹").append(unrealizedGains).append("\n");
                }
                ctx.append("Holdings Count: ").append(holdingsCount).append("\n\n");
            } else {
                log.warn("No portfolio data found for user {}. Investments table is empty and no snapshot exists.", userId);
            }
        }
    }

    private void appendAfternoonSnapshots(StringBuilder ctx, String userId) {
        List<PortfolioSnapshot> today = snapshotRepository.findRecentSnapshots(
                userId, LocalDateTime.now().minusHours(12));
        if (today.size() >= 2) {
            PortfolioSnapshot first = today.getLast();
            PortfolioSnapshot last = today.getFirst();
            if (first.getCurrentValue() != null && last.getCurrentValue() != null) {
                ctx.append("## Intraday Change\n");
                ctx.append("Open Value: ₹").append(first.getCurrentValue()).append("\n");
                ctx.append("Close Value: ₹").append(last.getCurrentValue()).append("\n\n");
            }
        }
    }

    private void appendActiveGoals(StringBuilder ctx, String userId) {
        List<FinancialGoal> goals = goalRepository.findActiveGoals(userId);
        if (!goals.isEmpty()) {
            ctx.append("## Financial Goals\n");
            for (FinancialGoal g : goals) {
                ctx.append("- ").append(g.getName())
                        .append(": Target ₹").append(g.getTargetAmount())
                        .append(", Current ₹").append(g.getCurrentAmount())
                        .append(", Progress ").append(g.getProgressPercentage()).append("%")
                        .append(", Status: ").append(g.getStatus())
                        .append(", Deadline: ").append(g.getTargetDate()).append("\n");
            }
            ctx.append("\n");
        }
    }

    private void appendRecentTransactions(StringBuilder ctx, String userId, int days) {
        List<Transaction> txns = transactionRepository.findRecentTransactions(
                userId, LocalDate.now().minusDays(days));
        if (!txns.isEmpty()) {
            ctx.append("## Recent Transactions (last ").append(days).append(" days)\n");
            txns.stream().limit(20).forEach(t ->
                    ctx.append("- ").append(t.getTransactionDate())
                            .append(" | ").append(t.getTransactionType())
                            .append(" | ").append(t.getName() != null ? t.getName() : t.getDescription())
                            .append(" | ₹").append(t.getAmount()).append("\n")
            );
            ctx.append("\n");
        }
    }

    /**
     * Phase 7: Use PersonalizedRelevanceScorer instead of generic findRecentByRelevance.
     * Each article is annotated with actionType, aligned goals, and correlation reason
     * so the LLM can reason with full investor-specific context.
     */
    private void appendTodaysNews(StringBuilder ctx, String userId, int maxArticles) {
        PersonalizedNewsResponse personalized = personalizedRelevanceScorer.score(userId, 1, maxArticles);
        List<PersonalizedNewsItem> items = personalized.news();
        if (items.isEmpty()) return;

        ctx.append("## Relevant News (Personalized)\n");
        for (PersonalizedNewsItem item : items) {
            NewsArticle n = item.article();
            ctx.append("- [score:").append(item.personalizedScore()).append("]")
               .append("[").append(n.getRelevanceLevel()).append("]")
               .append("[").append(n.getSentiment()).append("] ")
               .append(n.getSource()).append(": ").append(n.getTitle());

            // Annotations for LLM reasoning
            if (!item.alignedGoalNames().isEmpty())
                ctx.append(" [Affects goals: ").append(String.join(", ", item.alignedGoalNames())).append("]");
            if (item.actionType() != null && item.actionType().name().equals("WATCH") == false)
                ctx.append(" [ActionType: ").append(item.actionType()).append("]");
            if (item.primaryCorrelationReason() != null)
                ctx.append(" [CorrelationReason: ").append(item.primaryCorrelationReason()).append("]");
            if (item.psychologicalGuardrail())
                ctx.append(" [PSYCHOLOGICAL_GUARDRAIL: adopt Voice of Reason tone]");
            if (item.anomalyAlert())
                ctx.append(" [ANOMALY_ALERT: consecutive circuit breaker detected]");

            if (n.getSummary() != null && !n.getSummary().isBlank())
                ctx.append(" — ").append(n.getSummary(), 0, Math.min(120, n.getSummary().length()));
            ctx.append("\n");
        }
        ctx.append("\n");
    }

    private void appendPolicyIntelligenceContext(StringBuilder ctx, String userId, String userMessage, int limit) {
        PolicyIntelligenceService.AdvisorPolicyContext policyContext =
                policyIntelligenceService.buildAdvisorContext(userId, userMessage, limit);

        if (policyContext.documents().isEmpty()
                && policyContext.eventCards().isEmpty()
                && policyContext.chunks().isEmpty()) {
            return;
        }

        ctx.append("## Policy Intelligence\n");
        if (!policyContext.eventCards().isEmpty()) {
            ctx.append("Policy event cards:\n");
            policyContext.eventCards().stream().limit(limit).forEach(impact -> {
                ctx.append("- Event: ").append(impact.authority())
                        .append(" ").append(impact.documentType())
                        .append(" | Force: ").append(impact.bindingLevel())
                        .append(" | Ref: ").append(impact.sourceReference() != null ? impact.sourceReference() : impact.documentTitle())
                        .append("\n  What changed: ").append(impact.actionType() != null ? impact.actionType() : "UNKNOWN")
                        .append(" affecting ").append(impact.subjectLabel());
                if (impact.affectedParty() != null && !impact.affectedParty().isBlank()) {
                    ctx.append(" | Who: ").append(impact.affectedParty());
                }
                ctx.append("\n  Mechanism: ")
                        .append(impact.transmissionChannel() != null ? impact.transmissionChannel() : "UNKNOWN")
                        .append(" | Direction: ").append(impact.direction())
                        .append(" | Horizon: ").append(impact.horizon())
                        .append(" | ").append(impact.impactSummary());
                if (impact.effectiveFrom() != null) {
                    ctx.append("\n  Effective: ").append(impact.effectiveFrom());
                }
                if (impact.implementationSummary() != null && !impact.implementationSummary().isBlank()) {
                    ctx.append(" | Implementation: ").append(impact.implementationSummary());
                }
                if (impact.surpriseClassification() != null) {
                    ctx.append("\n  Surprise: ").append(impact.surpriseClassification());
                }
                if (impact.legalForceRank() != null || impact.marketMovingPower() != null) {
                    ctx.append(" | LegalForce: ").append(impact.legalForceRank() != null ? impact.legalForceRank() : "n/a")
                            .append(" | MarketMovingPower: ").append(impact.marketMovingPower() != null ? impact.marketMovingPower() : "n/a");
                }
                if (impact.confidenceScore() != null) {
                    ctx.append(" | Confidence: ").append(String.format("%.2f", impact.confidenceScore()));
                }
                if (impact.falsificationSignal() != null && !impact.falsificationSignal().isBlank()) {
                    ctx.append("\n  Watch/Falsify: ").append(impact.falsificationSignal());
                }
                ctx.append("\n");
            });
        }

        if (!policyContext.chunks().isEmpty()) {
            ctx.append("Policy source excerpts:\n");
            policyContext.chunks().stream().limit(limit).forEach(chunk -> {
                String excerpt = chunk.content().length() > 220
                        ? chunk.content().substring(0, 220) + "..."
                        : chunk.content();
                ctx.append("- ").append(chunk.authority())
                        .append(" | ").append(chunk.documentTitle());
                if (chunk.citationLabel() != null && !chunk.citationLabel().isBlank()) {
                    ctx.append(" | ").append(chunk.citationLabel());
                }
                ctx.append(" — ").append(excerpt).append("\n");
            });
        }

        ctx.append("\n");
    }

    /**
     * Injects individual holdings with exposure %, sector, P&L, and sector breakdown.
     * Falls back to the latest StockPriceHistory close if Investment.currentPrice is null
     * (e.g. prices fetched but not yet synced back).
     */
    private void appendPortfolioHoldings(StringBuilder ctx, String userId) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return;

        BigDecimal totalCost = investmentRepository.totalInvestmentCost(userId);
        double total = totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost.doubleValue() : 1.0;

        // Pre-fetch latest price history for all symbols in one pass
        Map<String, org.amit.finwise.cfo.model.StockPriceHistory> latestPriceMap = new HashMap<>();
        for (Investment inv : investments) {
            if (inv.getSymbol() != null) {
                stockPriceHistoryRepository.findTopBySymbolOrderByPriceDateDesc(
                        inv.getSymbol().toUpperCase()).ifPresent(h -> latestPriceMap.put(inv.getSymbol().toUpperCase(), h));
            }
        }

        ctx.append("## Holdings (Active Positions)\n");
        investments.stream()
                .sorted(Comparator.comparing(
                        inv -> inv.getTotalCost() != null ? inv.getTotalCost().negate() : BigDecimal.ZERO))
                .forEach(inv -> {
                    double exposure = inv.getTotalCost() != null
                            ? (inv.getTotalCost().doubleValue() / total) * 100 : 0;

                    // Resolve current value: prefer Investment field, fall back to price history
                    String currentValueStr = "N/A";
                    String pnlStr = "N/A";
                    String dayChangeStr = "";
                    if (inv.getCurrentValue() != null) {
                        currentValueStr = "₹" + inv.getCurrentValue();
                        pnlStr = inv.getGainLossPercentage() != null
                                ? String.format("%+.1f%%", inv.getGainLossPercentage().doubleValue()) : "N/A";
                    } else if (inv.getSymbol() != null) {
                        org.amit.finwise.cfo.model.StockPriceHistory h = latestPriceMap.get(inv.getSymbol().toUpperCase());
                        if (h != null && h.getClosePrice() != null && inv.getQuantity() != null) {
                            BigDecimal cv = h.getClosePrice().multiply(inv.getQuantity())
                                    .setScale(2, BigDecimal.ROUND_HALF_UP);
                            currentValueStr = "₹" + cv + " (price: ₹" + h.getClosePrice() + " as of " + h.getPriceDate() + ")";
                            if (inv.getTotalCost() != null && inv.getTotalCost().compareTo(BigDecimal.ZERO) != 0) {
                                double pnlPct = cv.subtract(inv.getTotalCost()).doubleValue()
                                        / inv.getTotalCost().doubleValue() * 100;
                                pnlStr = String.format("%+.1f%%", pnlPct);
                            }
                        }
                    }

                    // Day change from price history
                    if (inv.getSymbol() != null) {
                        org.amit.finwise.cfo.model.StockPriceHistory h = latestPriceMap.get(inv.getSymbol().toUpperCase());
                        if (h != null && h.getPriceChangePercent() != null) {
                            dayChangeStr = " | Day: " + String.format("%+.2f%%", h.getPriceChangePercent());
                            if (Boolean.TRUE.equals(h.getHitUpperCircuit()))
                                dayChangeStr += " ⚡UPPER_CIRCUIT";
                            else if (Boolean.TRUE.equals(h.getHitLowerCircuit()))
                                dayChangeStr += " ⚡LOWER_CIRCUIT";
                        }
                    }

                    ctx.append("- ").append(inv.getSymbol() != null ? inv.getSymbol() : inv.getName())
                       .append(" [").append(inv.getSector() != null ? inv.getSector() : "?").append("]")
                       .append(" | Invested: ₹").append(inv.getTotalCost())
                       .append(" | Current: ").append(currentValueStr)
                       .append(" | P&L: ").append(pnlStr)
                       .append(dayChangeStr)
                       .append(" | Exposure: ").append(String.format("%.1f%%", exposure))
                       .append("\n");
                });

        // Sector breakdown
        Map<String, Double> sectorExposure = new LinkedHashMap<>();
        for (Investment inv : investments) {
            if (inv.getSector() != null && inv.getTotalCost() != null) {
                sectorExposure.merge(inv.getSector(), inv.getTotalCost().doubleValue() / total * 100, Double::sum);
            }
        }
        if (!sectorExposure.isEmpty()) {
            ctx.append("\n## Sector Exposure\n");
            sectorExposure.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .forEach(e -> ctx.append("- ").append(e.getKey())
                            .append(": ").append(String.format("%.1f%%", e.getValue()))
                            .append(e.getValue() > 40 ? " ⚠ CONCENTRATED" : "")
                            .append("\n"));
        }
        ctx.append("\n");
    }

    /**
     * Phase 2: Replaces the raw price-string dump with labeled technical conclusions.
     * TechnicalAnalysisService does all the arithmetic; the LLM receives conclusions,
     * not raw numbers to calculate from.
     */
    private void appendRecentPriceTrends(StringBuilder ctx, String userId, int ignored) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return;

        List<Investment> topHoldings = investments.stream()
                .filter(inv -> inv.getSymbol() != null && inv.getTotalCost() != null)
                .sorted(Comparator.comparing(inv -> inv.getTotalCost().negate()))
                .limit(8)
                .toList();

        if (topHoldings.isEmpty()) return;

        ctx.append("## Technical Signals (Top Holdings)\n");

        boolean anyData = false;
        for (Investment inv : topHoldings) {
            String sym = inv.getSymbol().toUpperCase();
            Optional<TechnicalSnapshot> snapOpt = technicalAnalysisService.analyze(sym);
            if (snapOpt.isEmpty()) {
                ctx.append("- ").append(sym).append(": INSUFFICIENT_HISTORY\n");
                continue;
            }
            anyData = true;
            TechnicalSnapshot snap = snapOpt.get();

            ctx.append("- ").append(sym)
               .append(" | Trend: ").append(snap.trend())
               .append(" | Momentum: ").append(snap.momentum());

            if (!Double.isNaN(snap.rsi14()))
                ctx.append(String.format(" | RSI: %.1f", snap.rsi14()));
            if (!Double.isNaN(snap.return5d()))
                ctx.append(String.format(" | 5d: %+.1f%%", snap.return5d()));
            if (!Double.isNaN(snap.return20d()))
                ctx.append(String.format(" | 20d: %+.1f%%", snap.return20d()));
            if (!Double.isNaN(snap.pctFrom52wHigh()))
                ctx.append(String.format(" | vs52wHigh: %+.1f%%", snap.pctFrom52wHigh()));
            if (!Double.isNaN(snap.sma50()))
                ctx.append(String.format(" | vs SMA50: %+.1f%%", snap.pctVsSma50()));
            if (!Double.isNaN(snap.sma200()))
                ctx.append(String.format(" | %s", snap.goldenCross() ? "GOLDEN_CROSS" : "DEATH_CROSS"));
            ctx.append("\n");
        }

        if (!anyData) {
            ctx.append("(No technical data available — price history not yet accumulated)\n");
        }
        ctx.append("\n");
    }

    /**
     * Maps today's top news articles to specific holdings using SectorCorrelationMap.
     * Turns generic sector-level signals into holding-specific impact statements.
     * Example: "Iran war news → Aviation -1.5 → IndiGo (8.2% of portfolio) → NEGATIVE"
     */
    private void appendSectorRiskMap(StringBuilder ctx, String userId) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return;

        BigDecimal totalCost = investmentRepository.totalInvestmentCost(userId);
        double total = totalCost.compareTo(BigDecimal.ZERO) > 0 ? totalCost.doubleValue() : 1.0;

        List<NewsArticle> recentNews = newsArticleRepository.findRecentByRelevance(
                LocalDate.now().minusDays(2));

        record ImpactLine(String newsTitle, String symbol, String sector, double exposure, String direction, double strength) {}
        List<ImpactLine> impactLines = new ArrayList<>();

        for (NewsArticle article : recentNews.stream().limit(8).toList()) {
            String categoryStr = article.getCategory() != null ? article.getCategory().name() : "";
            Map<String, Double> sectorImpacts = SectorCorrelationMap.evaluate(
                    article.getTitle(), categoryStr, article.getSentiment());

            for (Investment inv : investments) {
                if (inv.getSector() == null) continue;
                Double impact = sectorImpacts.get(inv.getSector());
                if (impact != null && Math.abs(impact) >= 1.0) {
                    double exposure = inv.getTotalCost() != null
                            ? (inv.getTotalCost().doubleValue() / total) * 100 : 0;
                    String shortTitle = article.getTitle().length() > 60
                            ? article.getTitle().substring(0, 60) + "…" : article.getTitle();
                    impactLines.add(new ImpactLine(
                            shortTitle,
                            inv.getSymbol() != null ? inv.getSymbol() : inv.getName(),
                            inv.getSector(), exposure,
                            impact > 0 ? "POSITIVE" : "NEGATIVE",
                            Math.abs(impact)));
                }
            }
        }

        if (!impactLines.isEmpty()) {
            ctx.append("## News → Holdings Impact\n");
            impactLines.stream()
                    .sorted(Comparator.comparingDouble(ImpactLine::strength).reversed())
                    .limit(10)
                    .forEach(il -> ctx.append("- \"").append(il.newsTitle()).append("\"")
                            .append(" → ").append(il.symbol())
                            .append(" (").append(il.sector()).append(", ").append(String.format("%.1f%%", il.exposure())).append(" exposure)")
                            .append(" | Impact: ").append(il.direction())
                            .append(" [strength: ").append(String.format("%.1f", il.strength())).append("/2.0]\n"));
            ctx.append("\n");
        }
    }

    /**
     * Computes a 0–100 news-sentiment risk score and injects it into context.
     * Derived from: negative article count + price volatility + portfolio trend + concentration.
     * This is NOT a quant risk measure — see appendQuantRiskDecomposition for real portfolio risk.
     */
    private void appendNewsSentimentRisk(StringBuilder ctx, String userId) {
        MarketContextService.MarketContextSnapshot mc = marketContextService.getMarketContext(userId);

        int riskScore = 0;
        // Negative article count (0–30)
        riskScore += Math.min(30, mc.highRelevanceNegativeCount3Days() * 5);
        // Price volatility (0–30)
        if (mc.priceBasedVolatility() > 0)
            riskScore += (int) Math.min(30, mc.priceBasedVolatility() * 3);
        // Portfolio declining (0–20)
        if (mc.portfolioTrend() < 0)
            riskScore += (int) Math.min(20, Math.abs(mc.portfolioTrend()) * 2);
        // Sector concentration (0–20)
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        BigDecimal totalCost = investmentRepository.totalInvestmentCost(userId);
        if (!investments.isEmpty() && totalCost.compareTo(BigDecimal.ZERO) > 0) {
            double total = totalCost.doubleValue();
            Map<String, Double> sectorExp = new HashMap<>();
            for (Investment inv : investments) {
                if (inv.getSector() != null && inv.getTotalCost() != null)
                    sectorExp.merge(inv.getSector(), inv.getTotalCost().doubleValue() / total, Double::sum);
            }
            double maxExposure = sectorExp.values().stream().mapToDouble(d -> d).max().orElse(0);
            if (maxExposure > 0.50) riskScore += 20;
            else if (maxExposure > 0.35) riskScore += 10;
        }
        riskScore = Math.min(100, riskScore);

        String riskLevel = riskScore >= 70 ? "HIGH" : riskScore >= 40 ? "MEDIUM" : "LOW";

        ctx.append("## News-Sentiment Risk Scorecard\n");
        ctx.append("(Note: this is a news-signal score, not quant portfolio risk — see Risk Decomposition above)\n");
        ctx.append("Sentiment Risk Score: ").append(riskScore).append("/100 (").append(riskLevel).append(")\n");
        ctx.append("Volatility Signal: ").append(mc.isVolatileMarket() ? "HIGH" : "NORMAL").append("\n");
        ctx.append("Negative News (3d): ").append(mc.highRelevanceNegativeCount3Days()).append(" HIGH-relevance articles\n");
        if (mc.priceBasedVolatility() > 0)
            ctx.append("Price Volatility: ±").append(String.format("%.1f%%", mc.priceBasedVolatility())).append(" avg across holdings\n");
        ctx.append("\n");
    }

    /**
     * Injects a quantitative ## Risk Decomposition block computed by PortfolioRiskService.
     * All numbers are derived from actual price history — the LLM narrates, it does not calculate.
     * Silently omitted when insufficient price history exists (< 60 trading-day observations).
     */
    private void appendQuantRiskDecomposition(StringBuilder ctx, String userId) {
        try {
            Optional<RiskDecomposition> rdOpt = portfolioRiskService.compute(userId);
            if (rdOpt.isEmpty()) return;

            RiskDecomposition rd = rdOpt.get();
            ctx.append("## Risk Decomposition (Quantitative)\n");

            if (rd.isLowConfidence()) {
                ctx.append("⚠ LOW_CONFIDENCE: insufficient price history for some holdings");
                if (!rd.excludedSymbols().isEmpty()) {
                    ctx.append(String.format(" — excluded (INSUFFICIENT_HISTORY): %s = %.1f%% of portfolio value NOT in these risk figures",
                            String.join(", ", rd.excludedSymbols()), rd.excludedWeightPct() * 100));
                }
                ctx.append("\n");
            }
            for (String note : rd.dataQualityNotes()) {
                ctx.append("⚠ ").append(note).append("\n");
            }

            ctx.append(String.format("Period: %s → %s (%d trading days)\n",
                    rd.seriesFrom(), rd.seriesTo(), rd.observationCount()));

            ctx.append(String.format("Annualized Volatility: %.2f%% (daily: %.3f%%)",
                    rd.annualizedVolatility() * 100, rd.dailyVolatility() * 100));
            if (rd.shrinkageIntensity() != null)
                ctx.append(String.format(" | Ledoit-Wolf shrinkage δ=%.2f", rd.shrinkageIntensity()));
            ctx.append("\n");

            ctx.append(String.format("Portfolio Beta (vs Nifty 50): %.2f\n", rd.portfolioBeta()));

            ctx.append(String.format(
                    "1-Day VaR 95%% — Parametric: ₹%.0f | Cornish-Fisher (fat-tail adjusted): ₹%.0f | Historical: ₹%.0f\n",
                    rd.var95Parametric(), rd.var95CornishFisher(), rd.var95Historical()));
            ctx.append(String.format(
                    "1-Day VaR 99%% — Parametric: ₹%.0f | Cornish-Fisher: ₹%.0f\n",
                    rd.var99Parametric(), rd.var99CornishFisher()));
            ctx.append(String.format("Expected Shortfall (CVaR 95%%): ₹%.0f\n", rd.cvar95()));
            ctx.append(String.format("Return Distribution: skew %.2f | excess kurtosis %.2f%s\n",
                    rd.returnSkewness(), rd.returnExcessKurtosis(),
                    rd.returnExcessKurtosis() > 1 ? " (fat tails — prefer Cornish-Fisher/Historical VaR)" : ""));

            ctx.append(String.format("Diversification Ratio: %.2f | Effective Bets (ENB): %.1f\n",
                    rd.diversificationRatio(), rd.effectiveNumberOfBets()));
            ctx.append(String.format("Name HHI: %.3f | Sector HHI: %.3f\n",
                    rd.nameHHI(), rd.sectorHHI()));

            if (!Double.isNaN(rd.sharpeRatio()))
                ctx.append(String.format("Sharpe Ratio: %.2f | ", rd.sharpeRatio()));
            if (!Double.isNaN(rd.sortinoRatio()))
                ctx.append(String.format("Sortino Ratio: %.2f\n", rd.sortinoRatio()));
            else ctx.append("\n");
            if (!Double.isNaN(rd.trackingErrorVsNifty()))
                ctx.append(String.format("Tracking Error vs Nifty: %.2f%%\n",
                        rd.trackingErrorVsNifty() * 100));

            // Top-3 risk contributors
            ctx.append("Top Risk Contributors (% of portfolio volatility):\n");
            rd.riskContributors().stream().limit(3).forEach(c ->
                    ctx.append(String.format("  - %s: %.1f%% | weight: %.1f%% | β: %.2f\n",
                            c.symbol(),
                            c.percentContributionToRisk() * 100,
                            c.weight() * 100,
                            c.beta())));

            ctx.append("Headline: ").append(rd.headline()).append("\n");

            // Time-weighted return — actual performance independent of SIP timing
            portfolioPerformanceService.computeTwrr(userId).ifPresent(twrr -> {
                ctx.append(String.format(
                        "TWRR (%s → %s): %.2f%% cumulative", twrr.from(), twrr.to(), twrr.twrr() * 100));
                if (!Double.isNaN(twrr.annualizedTwrr()))
                    ctx.append(String.format(" | %.2f%% annualized", twrr.annualizedTwrr() * 100));
                ctx.append(" — use this, not cost-basis P&L, when judging performance under SIPs\n");
                if (!Double.isNaN(twrr.annualizedBenchmarkTwrr())) {
                    ctx.append(String.format(
                            "vs Nifty 50 (same window): %.2f%% annualized | Active return: %+.2f%%",
                            twrr.annualizedBenchmarkTwrr() * 100, twrr.activeReturnAnnualized() * 100));
                    if (!Double.isNaN(twrr.informationRatio()))
                        ctx.append(String.format(" | Information Ratio: %.2f", twrr.informationRatio()));
                    ctx.append(" — a TWRR without the benchmark is uninterpretable; judge skill on active return\n");
                }
            });
            ctx.append("\n");

        } catch (Exception e) {
            log.warn("[CFO] Risk decomposition failed, skipping block: {}", e.getMessage());
        }
    }

    /**
     * Injects a ## Factor Risk Model block computed by FactorModelService (Phase 8).
     * Per-holding OLS betas vs MKT/SIZE/own-sector index, portfolio factor exposures,
     * and the systematic/idiosyncratic variance split. The LLM narrates, it does not
     * calculate. Silently omitted when index history or holdings data is insufficient.
     */
    private void appendFactorRiskReport(StringBuilder ctx, String userId) {
        try {
            Optional<FactorRiskReport> frOpt = factorModelService.compute(userId);
            if (frOpt.isEmpty()) return;

            FactorRiskReport fr = frOpt.get();
            ctx.append("## Factor Risk Model (Quantitative)\n");

            if (fr.isLowConfidence()) {
                ctx.append("⚠ LOW_CONFIDENCE: factor estimates degraded — see notes below\n");
            }
            for (String note : fr.dataQualityNotes()) {
                ctx.append("⚠ ").append(note).append("\n");
            }

            ctx.append(String.format("Window: %s → %s | Factors: %s\n",
                    fr.seriesFrom(), fr.seriesTo(), String.join(", ", fr.factorNames())));

            ctx.append("Portfolio factor exposures: ");
            ctx.append(fr.portfolioFactorBetas().entrySet().stream()
                    .map(e -> String.format("%s β=%.2f", e.getKey(), e.getValue()))
                    .collect(Collectors.joining(" | ")));
            ctx.append("\n");

            if (!Double.isNaN(fr.totalVolAnnualized())) {
                ctx.append(String.format(
                        "Volatility split — Systematic: %.2f%% | Idiosyncratic: %.2f%% | Model total: %.2f%% (%.0f%% systematic)\n",
                        fr.systematicVolAnnualized() * 100,
                        fr.idiosyncraticVolAnnualized() * 100,
                        fr.totalVolAnnualized() * 100,
                        fr.systematicSharePct()));
                ctx.append("Systematic variance by factor: ");
                ctx.append(fr.factorVarianceSharePct().entrySet().stream()
                        .map(e -> String.format("%s %.0f%%", e.getKey(), e.getValue()))
                        .collect(Collectors.joining(" | ")));
                ctx.append("\n");
                if (!Double.isNaN(fr.directVolAnnualized())) {
                    ctx.append(String.format(
                            "Cross-check — direct covariance (wᵀΣw) vol: %.2f%% vs model %.2f%%; the gap is estimation noise, both are valid estimates\n",
                            fr.directVolAnnualized() * 100, fr.totalVolAnnualized() * 100));
                }
            }

            ctx.append("Per-holding factor betas (* = |t|≥2; t-stats are HAC-naive):\n");
            fr.holdings().stream().limit(8).forEach(h -> {
                StringBuilder line = new StringBuilder(String.format(
                        "  - %s (w %.1f%%): MKT %.2f%s", h.symbol(), h.weight() * 100,
                        h.betaMkt(), h.isSignificant("MKT") ? "*" : ""));
                if (h.betaSize() != null) {
                    line.append(String.format(" | SIZE %.2f%s", h.betaSize(),
                            h.isSignificant("SIZE") ? "*" : ""));
                }
                if (h.betaSector() != null) {
                    line.append(String.format(" | %s %.2f%s", h.sectorFactor(), h.betaSector(),
                            h.isSignificant(h.sectorFactor()) ? "*" : ""));
                }
                line.append(String.format(" | R² %.2f | idio vol %.1f%%",
                        h.rSquared(), h.idioVolAnnualized() * 100));
                ctx.append(line).append("\n");
            });

            ctx.append("Headline: ").append(fr.headline()).append("\n\n");

        } catch (Exception e) {
            log.warn("[CFO] Factor risk report failed, skipping block: {}", e.getMessage());
        }
    }

    /**
     * Injects a ## Tax Planning block: LTCG exemption headroom, harvestable lots,
     * STCG→LTCG boundary waits, and loss-harvest candidates. All rupee figures
     * are computed by TaxHarvestingService — the LLM narrates, it does not calculate.
     * Suggestions only; nothing is ever executed.
     */
    private void appendTaxPlanning(StringBuilder ctx, String userId) {
        try {
            var plan = taxHarvestingService.suggest(userId);
            boolean hasContent = plan.exemptionHeadroom().doubleValue() > 0
                    || !plan.exemptionHarvest().isEmpty()
                    || !plan.boundaryWait().isEmpty()
                    || !plan.lossHarvest().isEmpty();
            if (!hasContent) return;

            ctx.append(String.format("## Tax Planning (FY %s → %s)\n", plan.fyStart(), plan.fyEnd()));
            ctx.append(String.format("LTCG exemption headroom remaining: ₹%.0f\n",
                    plan.exemptionHeadroom().doubleValue()));

            if (!plan.exemptionHarvest().isEmpty()) {
                ctx.append("Tax-free LTCG harvest candidates (sell + optional rebuy; rebuy resets holding period):\n");
                plan.exemptionHarvest().stream().limit(3).forEach(c ->
                        ctx.append(String.format("  - %s (lot of %s): sell ~%.0f units → realize ₹%.0f LTCG inside the exemption, saving ₹%.0f future tax\n",
                                c.symbol(), c.buyDate(), c.unitsToSell(), c.ltcgRealized(), c.taxSavedVsLater())));
            }
            if (!plan.boundaryWait().isEmpty()) {
                ctx.append("Lots about to turn long-term — selling now wastes the lower LTCG rate:\n");
                plan.boundaryWait().stream().limit(3).forEach(c ->
                        ctx.append(String.format("  - %s (lot of %s): long-term in %d days; waiting saves ₹%.0f on ₹%.0f gain\n",
                                c.symbol(), c.buyDate(), c.daysToLongTerm(), c.taxSavingIfWaited(), c.unrealizedGain())));
            }
            if (!plan.lossHarvest().isEmpty()) {
                ctx.append("Loss-harvest candidates (realizing the loss offsets gains):\n");
                plan.lossHarvest().stream().limit(3).forEach(c ->
                        ctx.append(String.format("  - %s (lot of %s): ₹%.0f unrealized loss — %s\n",
                                c.symbol(), c.buyDate(), c.unrealizedLoss(), c.setOffScope())));
            }
            plan.notes().stream().limit(2).forEach(n -> ctx.append("⚠ ").append(n).append("\n"));
            ctx.append("\n");
        } catch (Exception e) {
            log.warn("[CFO] Tax planning block failed, skipping: {}", e.getMessage());
        }
    }

    /** Phase 7: Prepend a concise market state line to every brief context. */
    private void appendMarketContextSummary(StringBuilder ctx, String userId) {
        MarketContextService.MarketContextSnapshot mc = marketContextService.getMarketContext(userId);
        ctx.append("## Market Context\n");
        ctx.append("Market: ").append(mc.overallMarketMood());
        if (mc.portfolioTrend() != 0)
            ctx.append(" — portfolio ").append(String.format("%+.1f%%", mc.portfolioTrend()))
               .append(" this week");
        if (mc.isVolatileMarket())
            ctx.append(", ").append(mc.highRelevanceNegativeCount3Days())
               .append(" high-relevance negative articles in 3 days");
        if (mc.priceBasedVolatility() > 0)
            ctx.append(", priceVolatility: ±").append(String.format("%.1f%%", mc.priceBasedVolatility()))
               .append(" avg across holdings");
        ctx.append("\n\n");
    }

    // ── Insights Queries ──────────────────────────────────────────────────────

    public Optional<AiInsight> getLatestBrief() {
        return insightRepository.findTopByUserIdAndInsightTypeOrderByCreatedAtDesc(
                defaultUserId, AiInsight.InsightType.DAILY_BRIEF);
    }

    public Optional<AiInsight> getLatestInsightByType(AiInsight.InsightType type) {
        return insightRepository.findTopByUserIdAndInsightTypeOrderByCreatedAtDesc(defaultUserId, type);
    }

    public Page<AiInsight> getInsights(int page, int size) {
        return insightRepository.findByUserIdOrderByCreatedAtDesc(defaultUserId, PageRequest.of(page, size));
    }

    // ── Portfolio Queries ─────────────────────────────────────────────────────

    public Optional<PortfolioSnapshot> getLatestPortfolioSnapshot() {
        return snapshotRepository.findTopByUserIdOrderBySnapshotTimeDesc(defaultUserId);
    }

    // ── Transaction Queries ───────────────────────────────────────────────────

    public Page<Transaction> getTransactions(int page, int size) {
        return transactionRepository.findByUserIdOrderByTransactionDateDesc(defaultUserId, PageRequest.of(page, size));
    }

    public List<Transaction> getRecentTransactions(int days) {
        return transactionRepository.findRecentTransactions(defaultUserId, LocalDate.now().minusDays(days));
    }

    // ── User Profile ──────────────────────────────────────────────────────────

    public Optional<UserProfile> getProfile() {
        return userProfileRepository.findByUserId(defaultUserId);
    }

    public UserProfile updateProfile(String name, String email, java.math.BigDecimal monthlyIncome,
                                     java.math.BigDecimal monthlyFixedExpenses, UserProfile.RiskAppetite riskAppetite,
                                     Integer investmentHorizonYears, java.math.BigDecimal targetMonthlySavings,
                                     String primaryGoalDescription, String additionalContext) {
        UserProfile profile = userProfileRepository.findByUserId(defaultUserId)
                .orElse(UserProfile.builder().userId(defaultUserId).build());

        if (name != null) profile.setName(name);
        if (email != null) profile.setEmail(email);
        if (monthlyIncome != null) profile.setMonthlyIncome(monthlyIncome);
        if (monthlyFixedExpenses != null) profile.setMonthlyFixedExpenses(monthlyFixedExpenses);
        if (riskAppetite != null) profile.setRiskAppetite(riskAppetite);
        if (investmentHorizonYears != null) profile.setInvestmentHorizonYears(investmentHorizonYears);
        if (targetMonthlySavings != null) profile.setTargetMonthlySavings(targetMonthlySavings);
        if (primaryGoalDescription != null) profile.setPrimaryGoalDescription(primaryGoalDescription);
        if (additionalContext != null) profile.setAdditionalContext(additionalContext);

        return userProfileRepository.save(profile);
    }
}
