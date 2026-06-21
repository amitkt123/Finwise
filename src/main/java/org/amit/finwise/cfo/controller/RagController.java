package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.rag.EventOutcomeService;
import org.amit.finwise.cfo.service.rag.EvidencePackService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cfo/rag")
@RequiredArgsConstructor
public class RagController {

    private final EvidencePackService evidencePackService;
    private final EventOutcomeService eventOutcomeService;
    private final InvestmentRepository investmentRepository;

    @GetMapping("/evidence")
    public List<EvidencePackService.EvidencePack> evidence(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return evidencePackService.retrieve(query, holdingSymbols(principal.getUsername()), limit);
    }

    @PostMapping("/enrich")
    public EventOutcomeService.EnrichResult enrich() {
        return eventOutcomeService.enrichOutcomes();
    }

    private Set<String> holdingSymbols(String userId) {
        return investmentRepository.findActiveInvestments(userId).stream()
                .map(Investment::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }
}
