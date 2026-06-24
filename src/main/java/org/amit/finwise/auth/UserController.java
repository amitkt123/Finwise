package org.amit.finwise.auth;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.UserProfile;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final CFOAdvisorService cfoAdvisorService;

    @GetMapping("/me/profile")
    public ResponseEntity<UserProfile> getProfile(
            @AuthenticationPrincipal UserDetails principal) {
        return cfoAdvisorService.getProfile(principal.getUsername())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/me/profile")
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
