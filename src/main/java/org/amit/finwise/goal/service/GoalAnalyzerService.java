package org.amit.finwise.goal.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoalAnalyzerService {

    private final FinancialGoalRepository goalRepository;

    @Transactional
    public FinancialGoal createGoal(String userId, String name, String description,
                                    FinancialGoal.GoalType type, BigDecimal targetAmount,
                                    LocalDate targetDate, FinancialGoal.GoalPriority priority) {
        long months = Math.max(1, ChronoUnit.MONTHS.between(LocalDate.now(), targetDate));
        BigDecimal requiredMonthly = requiredMonthlyContribution(
                targetAmount, BigDecimal.ZERO, null, months);
        FinancialGoal goal = FinancialGoal.builder()
                .userId(userId).name(name).description(description).type(type)
                .targetAmount(targetAmount).currentAmount(BigDecimal.ZERO)
                .targetDate(targetDate).startDate(LocalDate.now()).priority(priority)
                .status(FinancialGoal.GoalStatus.ON_TRACK)
                .requiredMonthlyAmount(requiredMonthly).progressPercentage(BigDecimal.ZERO)
                .build();
        log.info("Created goal: {} with target: {}", name, targetAmount);
        return goalRepository.save(goal);
    }

    @Transactional
    public FinancialGoal updateGoalProgress(Long goalId, BigDecimal amountAdded) {
        FinancialGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new RuntimeException("Goal not found"));
        BigDecimal newAmount = goal.getCurrentAmount().add(amountAdded);
        BigDecimal progress = newAmount.divide(goal.getTargetAmount(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        goal.setCurrentAmount(newAmount);
        goal.setProgressPercentage(progress);
        goal.setStatus(evaluateGoalStatus(goal));
        return goalRepository.save(goal);
    }

    public FinancialGoal.GoalStatus evaluateGoalStatus(FinancialGoal goal) {
        long totalMonths = Math.max(1, ChronoUnit.MONTHS.between(goal.getStartDate(), goal.getTargetDate()));
        long elapsed = Math.max(0, ChronoUnit.MONTHS.between(goal.getStartDate(), LocalDate.now()));
        BigDecimal expected = BigDecimal.valueOf(
                expectedProgressPct(elapsed, totalMonths, goal.getExpectedAnnualReturn()));
        BigDecimal variance = goal.getProgressPercentage().subtract(expected);
        if (goal.getProgressPercentage().compareTo(BigDecimal.valueOf(100)) >= 0) return FinancialGoal.GoalStatus.ACHIEVED;
        if (variance.compareTo(BigDecimal.valueOf(-10)) < 0) return FinancialGoal.GoalStatus.OFF_TRACK;
        if (variance.compareTo(BigDecimal.valueOf(-5)) < 0) return FinancialGoal.GoalStatus.AT_RISK;
        return FinancialGoal.GoalStatus.ON_TRACK;
    }

    /**
     * Expected progress (%) after {@code elapsed} of {@code totalMonths} months.
     *
     * An SIP-funded corpus accumulates as an annuity, ((1+i)^m − 1)/i — convex,
     * not linear: compounding does most of its work late. A linear expectation
     * overstates how far behind a goal is in its early months and understates
     * it near the end. The annuity-accumulation ratio
     *
     *   E_m / E_M = ((1+i)^m − 1) / ((1+i)^M − 1)
     *
     * needs no SIP amount and degenerates to m/M as i → 0.
     */
    static double expectedProgressPct(long elapsed, long totalMonths, BigDecimal expectedAnnualReturnPct) {
        if (totalMonths <= 0) return 100.0;
        if (expectedAnnualReturnPct == null || expectedAnnualReturnPct.compareTo(BigDecimal.ZERO) <= 0) {
            return 100.0 * elapsed / totalMonths;
        }
        double i = Math.pow(1.0 + expectedAnnualReturnPct.doubleValue() / 100.0, 1.0 / 12.0) - 1.0;
        return 100.0 * (Math.pow(1.0 + i, elapsed) - 1.0) / (Math.pow(1.0 + i, totalMonths) - 1.0);
    }

    public GoalAnalysis analyzeGoal(Long goalId) {
        FinancialGoal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new RuntimeException("Goal not found"));
        GoalAnalysis analysis = new GoalAnalysis();
        analysis.goal = goal;
        analysis.daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), goal.getTargetDate());
        analysis.monthsRemaining = ChronoUnit.MONTHS.between(LocalDate.now(), goal.getTargetDate());
        analysis.amountNeeded = goal.getTargetAmount().subtract(goal.getCurrentAmount());
        if (goal.getInflationRate() != null && analysis.monthsRemaining > 0) {
            BigDecimal factor = BigDecimal.ONE.add(
                    goal.getInflationRate().divide(BigDecimal.valueOf(100 * 12), 6, RoundingMode.HALF_UP))
                    .pow((int) analysis.monthsRemaining);
            analysis.inflationAdjustedTarget = goal.getTargetAmount().multiply(factor);
            analysis.amountNeeded = analysis.inflationAdjustedTarget.subtract(goal.getCurrentAmount());
        } else {
            analysis.inflationAdjustedTarget = goal.getTargetAmount();
        }
        if (analysis.monthsRemaining > 0) {
            analysis.requiredMonthlySavings = requiredMonthlyContribution(
                    analysis.inflationAdjustedTarget, goal.getCurrentAmount(),
                    goal.getExpectedAnnualReturn(), analysis.monthsRemaining);
        }
        analysis.status = goal.getStatus();
        analysis.recommendations = generateRecommendations(goal, analysis);
        return analysis;
    }

    /**
     * Monthly contribution required to reach {@code futureTarget} in {@code months},
     * assuming contributions earn {@code expectedAnnualReturnPct} (e.g. 12 = 12% p.a.)
     * and the existing corpus compounds at the same rate:
     *
     *   PMT = (FV − corpus×(1+r)^n) × r / ((1+r)^n − 1)
     *
     * Falls back to linear FV/N when no return assumption is set — correct only
     * for zero-yield savings, but the safest default.
     */
    static BigDecimal requiredMonthlyContribution(BigDecimal futureTarget,
                                                  BigDecimal currentCorpus,
                                                  BigDecimal expectedAnnualReturnPct,
                                                  long months) {
        if (months <= 0) return futureTarget.subtract(currentCorpus).max(BigDecimal.ZERO);
        if (currentCorpus == null) currentCorpus = BigDecimal.ZERO;

        if (expectedAnnualReturnPct == null
                || expectedAnnualReturnPct.compareTo(BigDecimal.ZERO) <= 0) {
            return futureTarget.subtract(currentCorpus).max(BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
        }

        double r = expectedAnnualReturnPct.doubleValue() / 100.0 / 12.0; // monthly rate
        double growthFactor = Math.pow(1.0 + r, months);
        double corpusFV = currentCorpus.doubleValue() * growthFactor;
        double gap = futureTarget.doubleValue() - corpusFV;
        if (gap <= 0) return BigDecimal.ZERO; // corpus alone compounds past the target

        double pmt = gap * r / (growthFactor - 1.0);
        return BigDecimal.valueOf(pmt).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> generateRecommendations(FinancialGoal goal, GoalAnalysis analysis) {
        List<String> recs = new ArrayList<>();
        switch (goal.getStatus()) {
            case ON_TRACK -> recs.add("You're on track! Continue with current savings plan.");
            case AT_RISK -> {
                recs.add("Your goal is at risk. Increase monthly savings to: " + analysis.requiredMonthlySavings);
                recs.add("Consider reviewing other expenses to find additional savings.");
            }
            case OFF_TRACK -> {
                recs.add("Your goal is off track!");
                recs.add("Required monthly savings: " + analysis.requiredMonthlySavings);
                recs.add("Consider extending the deadline or increasing contributions.");
            }
            case ACHIEVED -> recs.add("Goal achieved! Consider setting a new one.");
            default -> {}
        }
        return recs;
    }

    public List<GoalAlert> getGoalAlerts(String userId) {
        return goalRepository.findAtRiskGoals(userId).stream().map(goal -> {
            GoalAnalysis analysis = analyzeGoal(goal.getId());
            GoalAlert alert = new GoalAlert();
            alert.goalId = goal.getId();
            alert.goalName = goal.getName();
            alert.status = goal.getStatus();
            alert.priority = goal.getPriority();
            alert.requiredMonthly = analysis.requiredMonthlySavings;
            alert.daysRemaining = analysis.daysRemaining;
            alert.progressPercent = goal.getProgressPercentage();
            alert.message = "%s needs ₹%s/month. At %s%%. %d days left.".formatted(
                    goal.getName(), analysis.requiredMonthlySavings, goal.getProgressPercentage(), analysis.daysRemaining);
            return alert;
        }).toList();
    }

    public static class GoalAnalysis {
        public FinancialGoal goal;
        public long daysRemaining;
        public long monthsRemaining;
        public BigDecimal amountNeeded;
        public BigDecimal inflationAdjustedTarget;
        public BigDecimal requiredMonthlySavings;
        public FinancialGoal.GoalStatus status;
        public List<String> recommendations;
    }

    public static class GoalAlert {
        public Long goalId;
        public String goalName;
        public FinancialGoal.GoalStatus status;
        public FinancialGoal.GoalPriority priority;
        public BigDecimal requiredMonthly;
        public long daysRemaining;
        public BigDecimal progressPercent;
        public String message;
    }
}
