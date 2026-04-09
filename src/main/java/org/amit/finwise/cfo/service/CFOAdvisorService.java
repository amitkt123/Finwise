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
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.repository.UserProfileRepository;
import org.amit.finwise.cfo.service.llm.LLMMessage;
import org.amit.finwise.cfo.service.llm.LLMProvider;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
            - Format responses in clean Markdown with sections.
            - Do not make up numbers — only use figures from the provided context.
            """;

    // ── Daily Brief ───────────────────────────────────────────────────────────

    /**
     * Generate the daily CFO morning brief. Skips if already generated today.
     */
    @Transactional
    public AiInsight generateDailyBrief() {
        String userId = defaultUserId;
        LocalDate today = LocalDate.now();

        // Skip if already generated today
        Optional<AiInsight> existing = insightRepository.findByUserIdAndDateAndType(
                userId, today, AiInsight.InsightType.DAILY_BRIEF);
        if (existing.isPresent()) {
            log.debug("Daily brief already generated for {}", today);
            return existing.get();
        }

        String context = buildDailyBriefContext(userId);
        String userPrompt = """
                Generate my daily CFO morning brief for %s.
                Include:
                1. **Portfolio Summary** - Key P&L metrics, day change
                2. **Market & News Highlights** - Top 3-5 relevant stories affecting my holdings
                3. **Goal Progress** - Quick status of my active financial goals
                4. **Today's Action Items** - 2-3 specific actions I should consider today
                5. **Risk Watch** - Any risks to monitor

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
                1. **Day's Performance** - How my portfolio performed today vs Nifty 50
                2. **News Impact Analysis** - Which news items affected my specific holdings
                3. **Opportunities Spotted** - Any buy/sell opportunities based on today's data
                4. **Rebalancing Check** - Is my portfolio allocation still aligned with my goals?
                5. **Tomorrow's Watch** - Key events or earnings to watch tomorrow

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
        String contextBlock = buildFullContext(defaultUserId);

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

        // Prepend market context summary line (Phase 7)
        appendMarketContextSummary(ctx, userId);

        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendActiveGoals(ctx, userId);
        appendRecentTransactions(ctx, userId, 7);
        appendTodaysNews(ctx, userId, 10);

        return ctx.toString();
    }

    private String buildAfterHoursContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
        appendAfternoonSnapshots(ctx, userId);
        appendTodaysNews(ctx, userId, 15);

        return ctx.toString();
    }

    private String buildFullContext(String userId) {
        StringBuilder ctx = new StringBuilder();

        appendMarketContextSummary(ctx, userId);
        appendUserProfile(ctx, userId);
        appendPortfolioSnapshot(ctx, userId);
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
