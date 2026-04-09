package org.amit.finwise.investment.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.service.InvestmentService;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<Investment> addInvestment(
            @RequestParam String userId,
            @RequestParam InvestmentType type,
            @RequestParam String symbol,
            @RequestParam String name,
            @RequestParam BigDecimal quantity,
            @RequestParam BigDecimal costPerUnit,
            @RequestParam(required = false) String platform) {
        return ResponseEntity.ok(investmentService.addInvestment(
                userId, type, symbol, name, LocalDate.now(), quantity, costPerUnit, platform));
    }

    @PutMapping("/investment/{id}/price")
    public ResponseEntity<Investment> updatePrice(@PathVariable Long id,
                                                   @RequestParam BigDecimal currentPrice) {
        return ResponseEntity.ok(investmentService.updateInvestmentPrice(id, currentPrice));
    }

    @GetMapping("/portfolio/analysis")
    public ResponseEntity<InvestmentService.PortfolioAnalysis> getPortfolioAnalysis(@RequestParam String userId) {
        return ResponseEntity.ok(investmentService.getPortfolioAnalysis(userId));
    }

    @GetMapping("/portfolio/rebalance")
    public ResponseEntity<InvestmentService.RebalancingRecommendation> getRebalancingRecommendation(@RequestParam String userId) {
        Map<String, BigDecimal> target = new HashMap<>();
        target.put("STOCK", BigDecimal.valueOf(50));
        target.put("BOND", BigDecimal.valueOf(30));
        target.put("FIXED_DEPOSIT", BigDecimal.valueOf(20));
        return ResponseEntity.ok(investmentService.getRebalancingRecommendation(userId, target));
    }

    @GetMapping("/investment/active")
    public ResponseEntity<List<Investment>> getActiveInvestments(@RequestParam String userId) {
        return ResponseEntity.ok(investmentRepository.findActiveInvestments(userId));
    }
}
