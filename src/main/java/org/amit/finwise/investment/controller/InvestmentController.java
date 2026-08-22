package org.amit.finwise.investment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.amit.finwise.investment.dto.AddInvestmentRequest;
import org.amit.finwise.investment.dto.InvestmentResponse;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.service.InvestmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class InvestmentController {

    private final InvestmentService investmentService;
    private final InvestmentRepository investmentRepository;

    @PostMapping("/investment")
    public ResponseEntity<InvestmentResponse> addInvestment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AddInvestmentRequest request) {
        return ResponseEntity.ok(InvestmentResponse.from(
                investmentService.addInvestment(
                        principal.getUsername(), request.type(), request.symbol(), request.name(),
                        LocalDate.now(), request.quantity(), request.costPerUnit(), request.platform(),
                        request.interestRate(), request.maturityDate(),
                        request.sumAssured(), request.annualPremium())));
    }

    @PutMapping("/investment/{id}/price")
    public ResponseEntity<InvestmentResponse> updatePrice(@PathVariable Long id,
                                                          @RequestParam BigDecimal currentPrice) {
        return ResponseEntity.ok(InvestmentResponse.from(
                investmentService.updateInvestmentPrice(id, currentPrice)));
    }

    @GetMapping("/portfolio/analysis")
    public ResponseEntity<InvestmentService.PortfolioAnalysis> getPortfolioAnalysis(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(investmentService.getPortfolioAnalysis(principal.getUsername()));
    }

    @GetMapping("/portfolio/rebalance")
    public ResponseEntity<InvestmentService.RebalancingRecommendation> getRebalancingRecommendation(
            @AuthenticationPrincipal UserDetails principal) {
        Map<String, BigDecimal> target = new HashMap<>();
        target.put("STOCK", BigDecimal.valueOf(50));
        target.put("BOND", BigDecimal.valueOf(30));
        target.put("FIXED_DEPOSIT", BigDecimal.valueOf(20));
        return ResponseEntity.ok(
                investmentService.getRebalancingRecommendation(principal.getUsername(), target));
    }

    @GetMapping("/investment/active")
    public ResponseEntity<List<InvestmentResponse>> getActiveInvestments(
            @AuthenticationPrincipal UserDetails principal) {
        return ResponseEntity.ok(
                investmentRepository.findActiveInvestments(principal.getUsername())
                        .stream().map(InvestmentResponse::from).toList());
    }
}
