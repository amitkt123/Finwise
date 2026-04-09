package org.amit.expensetracker.goal.controller;

import lombok.RequiredArgsConstructor;
import org.amit.expensetracker.goal.model.FinancialGoal;
import org.amit.expensetracker.goal.repository.FinancialGoalRepository;
import org.amit.expensetracker.goal.service.GoalAnalyzerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class GoalController {

    private final GoalAnalyzerService goalAnalyzerService;
    private final FinancialGoalRepository goalRepository;

    @PostMapping("/goal")
    public ResponseEntity<FinancialGoal> createGoal(
            @RequestParam String userId,
            @RequestParam String name,
            @RequestParam String description,
            @RequestParam FinancialGoal.GoalType type,
            @RequestParam BigDecimal targetAmount,
            @RequestParam LocalDate targetDate,
            @RequestParam(required = false) FinancialGoal.GoalPriority priority) {
        FinancialGoal.GoalPriority p = priority != null ? priority : FinancialGoal.GoalPriority.MEDIUM;
        return ResponseEntity.ok(goalAnalyzerService.createGoal(userId, name, description, type, targetAmount, targetDate, p));
    }

    @PutMapping("/goal/{id}/progress")
    public ResponseEntity<FinancialGoal> updateGoalProgress(@PathVariable Long id,
                                                              @RequestParam BigDecimal amountAdded) {
        return ResponseEntity.ok(goalAnalyzerService.updateGoalProgress(id, amountAdded));
    }

    @GetMapping("/goal/{id}/analysis")
    public ResponseEntity<GoalAnalyzerService.GoalAnalysis> analyzeGoal(@PathVariable Long id) {
        return ResponseEntity.ok(goalAnalyzerService.analyzeGoal(id));
    }

    @GetMapping("/goal/alerts")
    public ResponseEntity<List<GoalAnalyzerService.GoalAlert>> getGoalAlerts(@RequestParam String userId) {
        return ResponseEntity.ok(goalAnalyzerService.getGoalAlerts(userId));
    }

    @GetMapping("/goal/active")
    public ResponseEntity<List<FinancialGoal>> getActiveGoals(@RequestParam String userId) {
        return ResponseEntity.ok(goalRepository.findActiveGoals(userId));
    }
}
