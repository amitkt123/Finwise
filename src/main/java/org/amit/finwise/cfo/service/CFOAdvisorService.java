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
import org.amit.finwise.cfo.service.llm.LLMMessage;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            - If policy evidence is provided, cite the authority and effective date where possible.
            - Format responses in clean Markdown with sections.
            - Do not make up numbers — only use figures from the provided context.
            """;

    // ── Daily Brief ───────────────────────────────────────────────────────────

    private static final int BRIEF_COOLDOWN_MINUTES = 120;

    /**
     * Generate the daily CFO morning brief.
     * Regenerates if no brief exists today, or if the last one is older than BRIEF_COOLDOWN_MINUTES.
     * This ensures a fresh brief is produced after each Groww sync + price fetch cycle,
     * rather than serving a stale 7:30 AM brief all day.
     */
    @Transactional
    public AiInsight generateDailyBrief() {
        String userId = defaultUserId;
        LocalDate today = LocalDate.now();

        Optional<AiInsight> existing = insightRepository.findByUserIdAndDateAndType(
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
                5. **Risk Scorecard** — Use the Market Risk Score from context; map risks to specific holdings

                Rules:
                - Reference exact holdings and exposure %% from context. Never say "no portfolio data".
                - For each news → holding impact, state: "[Stock] has X%% exposure, [POSITIVE/NEGATIVE] impact due to [reason]"
                - If Risk Score ≥ 70 → open with a CAUTION banner

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

    @Transactional
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
    @Transactional
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

    @Transactional
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
        String contextBlock = buildFullContext(defaultUserId, userMessage);

        // Inject context as first system message if history is empty
        List<LLMMessage> messages;
        if (conversationHistory.isEmpty()) {
            messages = List.of(
                    LLMMessage.system(CFO_SYSTEM_PROMPT + "\n\n## Your Current Financial Context:\n" + contextBlock),
                    LLMMessage.user(userMessage)
            );
        } else {
            messages = new java.util.ArrayList<>(conversationHistory);
            messages.add(LLMMessage.user(userMessage));
        }

        String response = llmProvider.chatWithHistory(messages);

        // Persist as CHAT_RESPONSE insight
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

    // ── Context Builders ───────────────────────────────────────────────────────

    private String buildDailyBriefContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendRiskScorecard(ctx, userId);
        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendRecentPriceTrends(ctx, userId, 5);
        appendSectorRiskMap(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, null, 6);
        appendActiveGoals(ctx, userId);
        appendRecentTransactions(ctx, userId, 7);
        appendTodaysNews(ctx, userId, 10);

        return ctx.toString();
    }

    private String buildAfterHoursContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendRiskScorecard(ctx, userId);
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
        appendRiskScorecard(ctx, userId);
        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendPortfolioHoldings(ctx, userId);
        appendRecentPriceTrends(ctx, userId, 5);
        appendSectorRiskMap(ctx, userId);
        appendPolicyIntelligenceContext(ctx, userId, userMessage, 8);
        appendActiveGoals(ctx, userId);
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
        snapshotRepository.findTopByUserIdOrderBySnapshotTimeDesc(userId).ifPresent(s -> {
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
        });
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
                && policyContext.impacts().isEmpty()
                && policyContext.chunks().isEmpty()) {
            return;
        }

        ctx.append("## Policy Intelligence\n");
        if (!policyContext.impacts().isEmpty()) {
            ctx.append("Relevant policy impacts:\n");
            policyContext.impacts().stream().limit(limit).forEach(impact -> {
                ctx.append("- ").append(impact.authority())
                        .append(" | ").append(impact.subjectLabel())
                        .append(" | ").append(impact.direction())
                        .append(" | ").append(impact.horizon())
                        .append(" | ").append(impact.impactSummary());
                if (impact.effectiveFrom() != null) {
                    ctx.append(" | Effective: ").append(impact.effectiveFrom());
                }
                if (impact.confidenceScore() != null) {
                    ctx.append(" | Confidence: ").append(String.format("%.2f", impact.confidenceScore()));
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
     * Injects a recent N-day price trend table for the top holdings by cost.
     * Gives the LLM concrete momentum data (not just today's snapshot) to reason about.
     */
    private void appendRecentPriceTrends(StringBuilder ctx, String userId, int days) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        if (investments.isEmpty()) return;

        // Focus on top 8 holdings by cost
        List<Investment> topHoldings = investments.stream()
                .filter(inv -> inv.getSymbol() != null && inv.getTotalCost() != null)
                .sorted(Comparator.comparing(inv -> inv.getTotalCost().negate()))
                .limit(8)
                .toList();

        if (topHoldings.isEmpty()) return;

        LocalDate since = LocalDate.now().minusDays(days);
        ctx.append("## Recent Price Trends (last ").append(days).append(" days)\n");

        for (Investment inv : topHoldings) {
            List<org.amit.finwise.cfo.model.StockPriceHistory> history =
                    stockPriceHistoryRepository.findRecentBySymbol(inv.getSymbol().toUpperCase(), since);
            if (history.isEmpty()) continue;

            // history is ordered DESC (newest first)
            ctx.append("- ").append(inv.getSymbol()).append(": ");
            List<String> entries = new ArrayList<>();
            for (int i = history.size() - 1; i >= 0; i--) {
                org.amit.finwise.cfo.model.StockPriceHistory h = history.get(i);
                String entry = h.getPriceDate().toString() + " ₹" + h.getClosePrice();
                if (h.getPriceChangePercent() != null)
                    entry += " (" + String.format("%+.1f%%", h.getPriceChangePercent()) + ")";
                if (Boolean.TRUE.equals(h.getHitUpperCircuit())) entry += "⚡U";
                else if (Boolean.TRUE.equals(h.getHitLowerCircuit())) entry += "⚡L";
                entries.add(entry);
            }
            ctx.append(String.join(" → ", entries)).append("\n");
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
     * Computes a 0–100 market risk score and injects it into context.
     * Derived from: negative article count + price volatility + portfolio trend + concentration.
     */
    private void appendRiskScorecard(StringBuilder ctx, String userId) {
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

        ctx.append("## Risk Scorecard\n");
        ctx.append("Market Risk Score: ").append(riskScore).append("/100 (").append(riskLevel).append(")\n");
        ctx.append("Volatility: ").append(mc.isVolatileMarket() ? "HIGH" : "NORMAL").append("\n");
        ctx.append("Negative News (3d): ").append(mc.highRelevanceNegativeCount3Days()).append(" HIGH-relevance articles\n");
        if (mc.priceBasedVolatility() > 0)
            ctx.append("Price Volatility: ±").append(String.format("%.1f%%", mc.priceBasedVolatility())).append(" avg across holdings\n");
        ctx.append("\n");
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
