package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.DismissedInsightCard;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.repository.DismissedInsightCardRepository;
import org.amit.finwise.cfo.service.insight.InsightCardService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightCardService insightCardService;
    private final DismissedInsightCardRepository dismissedRepo;

    @GetMapping
    public ResponseEntity<List<InsightCard>> getInsightCards(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(insightCardService.generate(principal.getUsername()));
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<Map<String, Object>> dismiss(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable String id) {
        String userId = principal.getUsername();
        if (!dismissedRepo.existsByUserIdAndCardId(userId, id)) {
            dismissedRepo.save(DismissedInsightCard.builder()
                    .userId(userId)
                    .cardId(id)
                    .build());
        }
        return ResponseEntity.ok(Map.of("dismissed", true, "cardId", id));
    }
}
