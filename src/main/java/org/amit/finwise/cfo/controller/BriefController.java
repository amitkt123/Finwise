package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.dto.InsightResponse;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.service.CFOAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/brief")
@RequiredArgsConstructor
public class BriefController {

    private final CFOAdvisorService cfoAdvisorService;

    @GetMapping("/daily")
    public ResponseEntity<InsightResponse> getDailyBrief(
            @AuthenticationPrincipal UserDetails principal) {
        AiInsight brief = cfoAdvisorService.generateDailyBrief(principal.getUsername());
        return ResponseEntity.ok(InsightResponse.from(brief));
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshBrief(
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal.getUsername();
        CompletableFuture.runAsync(() -> cfoAdvisorService.generateDailyBrief(userId));
        return ResponseEntity.ok(Map.of("status", "refresh triggered"));
    }
}
