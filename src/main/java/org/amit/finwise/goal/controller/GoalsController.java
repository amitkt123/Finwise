package org.amit.finwise.goal.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.model.GoalSimulationResult;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.goal.service.MonteCarloGoalService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/goals")
@RequiredArgsConstructor
public class GoalsController {

    private final MonteCarloGoalService monteCarloGoalService;
    private final FinancialGoalRepository goalRepository;

    @GetMapping("/{id}/montecarlo")
    public ResponseEntity<GoalSimulationResult> simulate(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @RequestParam(required = false) Double sip) {
        FinancialGoal goal = goalRepository.findById(id)
                .orElse(null);
        if (goal == null || !goal.getUserId().equals(principal.getUsername())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(monteCarloGoalService.simulate(goal, sip));
    }
}
