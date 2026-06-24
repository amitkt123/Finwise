package org.amit.finwise.company.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.company.model.CompanyProfile;
import org.amit.finwise.company.service.CompanyProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 — single-company intelligence view.
 * {@code GET /api/company/{symbol}} returns the six-card beginner profile.
 */
@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyProfileController {

    private final CompanyProfileService companyProfileService;

    @GetMapping("/{symbol}")
    public ResponseEntity<CompanyProfile> getProfile(
            @PathVariable String symbol,
            @AuthenticationPrincipal UserDetails principal) {
        String userId = principal != null ? principal.getUsername() : null;
        return ResponseEntity.ok(companyProfileService.getProfile(symbol, userId));
    }
}
