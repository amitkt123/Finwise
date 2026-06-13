package org.amit.finwise.cfo.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.service.rag.EventOutcomeService;
import org.amit.finwise.cfo.service.rag.EvidencePackService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inspection + manual-trigger endpoints for the outcome-linked news RAG (DF-6).
 * Read paths help verify clustering/outcomes; the POST trigger lets ops force an
 * enrichment pass without waiting for the nightly schedule.
 */
@RestController
@RequestMapping("/api/cfo/rag")
@RequiredArgsConstructor
public class RagController {

    private final EvidencePackService evidencePackService;
    private final EventOutcomeService eventOutcomeService;
    private final InvestmentRepository investmentRepository;

    @Value("${cfo.user.id}")
    private String userId;

    /**
     * Retrieve evidence packs for a free-text query, ranked by the hybrid score
     * (similarity × recency × portfolio overlap). Mirrors what brief generation
     * will consume.
     */
    @GetMapping("/evidence")
    public List<EvidencePackService.EvidencePack> evidence(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        return evidencePackService.retrieve(query, holdingSymbols(), limit);
    }

    /** Force an outcome-enrichment pass now (idempotent). */
    @PostMapping("/enrich")
    public EventOutcomeService.EnrichResult enrich() {
        return eventOutcomeService.enrichOutcomes();
    }

    private Set<String> holdingSymbols() {
        return investmentRepository.findActiveInvestments(userId).stream()
                .map(Investment::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }
}
