package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.policy.model.*;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.amit.finwise.policy.repository.PolicyDocumentRepository;
import org.amit.finwise.policy.repository.PolicyDocumentVersionRepository;
import org.amit.finwise.policy.repository.PolicyImpactRepository;
import org.amit.finwise.cfo.service.macro.PolicyQuantSignalService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyIntelligenceService {

    private final PolicyDocumentRepository documentRepository;
    private final PolicyDocumentVersionRepository versionRepository;
    private final PolicyChunkRepository chunkRepository;
    private final PolicyImpactRepository impactRepository;
    private final PolicyChunkingService chunkingService;
    private final InvestmentRepository investmentRepository;
    private final FinancialGoalRepository goalRepository;
    private final PolicyEmbeddingService policyEmbeddingService;
    private final PolicyHybridRetriever hybridRetriever;
    private final PolicyDiffService policyDiffService;
    private final PolicyNotificationService policyNotificationService;

    private final PolicyQuantSignalService policyQuantSignalService;

    @Transactional
    public PolicyDocument ingestDocument(PolicyIngestionRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("Policy document title is required");
        }
        if (request.rawText() == null || request.rawText().isBlank()) {
            throw new IllegalArgumentException("Policy document rawText is required");
        }

        String normalizedText = normalize(request.rawText());
        String contentHash = sha256(normalizedText);
        String documentKey = resolveDocumentKey(request);

        PolicyDocument document = resolveExistingDocument(request, documentKey)
                .orElseGet(() -> PolicyDocument.builder()
                        .documentKey(documentKey)
                        .authority(request.authority())
                        .documentType(request.documentType())
                        .bindingLevel(request.bindingLevel())
                        .policyArea(request.policyArea())
                        .build());

        document.setAuthority(request.authority());
        document.setDocumentType(request.documentType());
        document.setBindingLevel(request.bindingLevel());
        document.setPolicyArea(request.policyArea());
        document.setStatus(request.status() != null ? request.status() : PolicyDocumentStatus.ACTIVE);
        document.setTitle(request.title());
        document.setSourceUrl(request.sourceUrl());
        document.setSourceReference(request.sourceReference());
        document.setExternalDocumentId(request.externalDocumentId());
        document.setPublishedDate(request.publishedDate());
        document.setEffectiveFrom(request.effectiveFrom());
        document.setEffectiveTo(request.effectiveTo());
        document.setTagsCsv(toCsv(request.tags()));
        document.setAffectedSectorsCsv(toCsv(request.affectedSectors()));
        document.setSummary(request.summary());
        document = documentRepository.save(document);

        Optional<PolicyDocumentVersion> currentVersionOpt = versionRepository.findByDocumentAndCurrentTrue(document);
        if (currentVersionOpt.isPresent() && currentVersionOpt.get().getContentHash().equals(contentHash)) {
            if (request.impacts() != null && !request.impacts().isEmpty()) {
                impactRepository.deleteByVersion(currentVersionOpt.get());
            }
            syncImpacts(document, currentVersionOpt.get(), request.impacts());
            return document;
        }

        currentVersionOpt.ifPresent(current -> {
            current.setCurrent(false);
            versionRepository.save(current);
        });

        int nextVersionNumber = currentVersionOpt
                .map(v -> v.getVersionNumber() + 1)
                .orElse(document.getLatestVersionNumber() != null ? document.getLatestVersionNumber() : 1);

        PolicyDocumentVersion version = PolicyDocumentVersion.builder()
                .document(document)
                .versionNumber(nextVersionNumber)
                .versionLabel(request.versionLabel())
                .supersedesReference(request.supersedesReference())
                .contentHash(contentHash)
                .current(true)
                .issuedAt(request.issuedAt())
                .effectiveFrom(request.effectiveFrom())
                .effectiveTo(request.effectiveTo())
                .summary(request.summary())
                .normalizedText(normalizedText)
                .metadataJson(request.metadataJson())
                .build();
        version = versionRepository.save(version);

        document.setLatestVersionNumber(nextVersionNumber);
        document.setSummary(request.summary());
        documentRepository.save(document);

        List<PolicyChunkingService.ChunkDraft> chunkDrafts = chunkingService.chunk(normalizedText);
        List<PolicyChunk> savedChunks = new ArrayList<>();
        for (PolicyChunkingService.ChunkDraft draft : chunkDrafts) {
            savedChunks.add(chunkRepository.save(PolicyChunk.builder()
                    .version(version)
                    .chunkIndex(draft.chunkIndex())
                    .heading(draft.heading())
                    .sectionPath(draft.sectionPath())
                    .citationLabel(draft.citationLabel())
                    .content(draft.content())
                    .keywordText(draft.keywordText())
                    .build()));
        }

        // Phase 2.1: embed the new version's chunks for hybrid retrieval (no-op when pgvector absent).
        policyEmbeddingService.embedAndStoreAll(savedChunks);

        syncImpacts(document, version, request.impacts());

        // Phase 3.1: async diff when superseding a previous version.
        Long prevVersionId = currentVersionOpt.map(PolicyDocumentVersion::getId).orElse(null);
        policyDiffService.diffAsync(document.getId(), prevVersionId, version.getId());

        // Phase 3.2: async notification for high-impact ingest.
        policyNotificationService.notifyAsync(document.getId(), version.getId());
        log.info("[Policy] Ingested document key={}, version={}, chunks={}",
                document.getDocumentKey(), nextVersionNumber, chunkDrafts.size());
        return document;
    }

    @Transactional(readOnly = true)
    public PolicySearchResult search(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            List<PolicyDocument> recent = documentRepository.findTop20ByOrderByUpdatedAtDesc();
            return new PolicySearchResult(toDocumentSummaries(recent), List.of(), List.of());
        }

        List<PolicyDocument> documents = limit(documentRepository.search(trimmed), limit);
        List<PolicyChunk> chunks = hybridRetriever.retrieve(trimmed, limit);
        List<PolicyImpact> impacts = limit(impactRepository.searchActiveImpacts(trimmed, LocalDate.now()), limit);
        List<PolicyEventCard> cards = toPolicyEventCards(impacts);
        policyQuantSignalService.process(cards);
        return new PolicySearchResult(
                toDocumentSummaries(documents),
                toChunkMatches(chunks),
                cards);
    }

    @Transactional(readOnly = true)
    public AdvisorPolicyContext buildAdvisorContext(String userId, String userMessage, int limit) {
        List<Investment> investments = investmentRepository.findActiveInvestments(userId);
        List<FinancialGoal> goals = goalRepository.findActiveGoals(userId);

        Set<String> sectors = investments.stream()
                .map(Investment::getSector)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> assetClasses = investments.stream()
                .map(Investment::getType)
                .filter(Objects::nonNull)
                .map(this::assetClassFor)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> factorExposures = inferFactorExposures(investments, userMessage);

        Set<String> queryTerms = new LinkedHashSet<>(sectors);
        queryTerms.addAll(assetClasses);
        queryTerms.addAll(factorExposures);
        queryTerms.addAll(extractGoalTerms(goals));
        queryTerms.addAll(extractMessageTerms(userMessage));

        List<PolicyImpact> matchedImpacts = new ArrayList<>();
        if (!sectors.isEmpty()) {
            matchedImpacts.addAll(limit(
                    impactRepository.findActiveImpactsForSubjects(
                            PolicySubjectType.SECTOR,
                            sectors,
                            LocalDate.now()),
                    limit));
        }
        matchedImpacts.addAll(findSubjectImpacts(PolicySubjectType.ASSET_CLASS, assetClasses, limit, matchedImpacts));
        matchedImpacts.addAll(findSubjectImpacts(PolicySubjectType.FACTOR, factorExposures, limit, matchedImpacts));
        matchedImpacts.addAll(findSubjectImpacts(
                PolicySubjectType.TAX_TOPIC,
                extractTaxTopics(userMessage, goals),
                limit,
                matchedImpacts));

        List<PolicyChunk> matchedChunks = new ArrayList<>();
        for (String term : queryTerms) {
            if (matchedChunks.size() >= limit) {
                break;
            }
            List<PolicyChunk> found = hybridRetriever.retrieve(term, limit);
            for (PolicyChunk chunk : found) {
                if (matchedChunks.stream().noneMatch(existing -> existing.getId().equals(chunk.getId()))) {
                    matchedChunks.add(chunk);
                }
                if (matchedChunks.size() >= limit) {
                    break;
                }
            }
        }

        List<PolicyDocument> documents = matchedChunks.stream()
                .map(chunk -> chunk.getVersion().getDocument())
                .distinct()
                .limit(limit)
                .toList();

        List<PolicyEventCard> advisorCards = toPolicyEventCards(matchedImpacts);
        policyQuantSignalService.process(advisorCards);
        return new AdvisorPolicyContext(
                toDocumentSummaries(documents),
                advisorCards,
                toChunkMatches(matchedChunks));
    }

    /**
     * Stock-level policy exposure (Roadmap Phase 2.3) — the bridge that closes the
     * single-company loop. Given a stock's resolved sector, factor exposures and
     * asset classes (computed caller-side from the gazetteer), it returns the
     * currently-active {@link PolicyEventCard}s whose subjects match, ranked by
     * market-moving power. Feeds Phase 1 Card 6.
     */
    @Transactional(readOnly = true)
    public List<PolicyEventCard> getStockPolicyExposure(String sector,
                                                        Set<String> factors,
                                                        Set<String> assetClasses,
                                                        int limit) {
        LocalDate today = LocalDate.now();
        Map<Long, PolicyImpact> byId = new LinkedHashMap<>();

        if (sector != null && !sector.isBlank()) {
            for (PolicyImpact impact : impactRepository.findActiveImpactsForSubjects(
                    PolicySubjectType.SECTOR, Set.of(sector.trim()), today)) {
                if (impact.getId() != null) byId.putIfAbsent(impact.getId(), impact);
            }
        }
        if (factors != null && !factors.isEmpty()) {
            for (PolicyImpact impact : impactRepository.findActiveImpactsForSubjects(
                    PolicySubjectType.FACTOR, factors, today)) {
                if (impact.getId() != null) byId.putIfAbsent(impact.getId(), impact);
            }
        }
        if (assetClasses != null && !assetClasses.isEmpty()) {
            for (PolicyImpact impact : impactRepository.findActiveImpactsForSubjects(
                    PolicySubjectType.ASSET_CLASS, assetClasses, today)) {
                if (impact.getId() != null) byId.putIfAbsent(impact.getId(), impact);
            }
        }

        return limit(toPolicyEventCards(new ArrayList<>(byId.values())), limit);
    }

    private List<PolicyImpact> findSubjectImpacts(PolicySubjectType subjectType,
                                                  Set<String> subjectKeys,
                                                  int limit,
                                                  List<PolicyImpact> existing) {
        if (subjectKeys.isEmpty() || existing.size() >= limit) {
            return List.of();
        }
        Set<Long> existingIds = existing.stream()
                .map(PolicyImpact::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return impactRepository.findActiveImpactsForSubjects(subjectType, subjectKeys, LocalDate.now())
                .stream()
                .filter(impact -> !existingIds.contains(impact.getId()))
                .limit(Math.max(0, limit - existing.size()))
                .toList();
    }

    private String assetClassFor(InvestmentType type) {
        return switch (type) {
            case STOCK, ETF -> "Equity";
            case MUTUAL_FUND -> "Mutual Funds";
            case BOND, FIXED_DEPOSIT, POST_OFFICE_SCHEME, PPF -> "Debt";
            case REAL_ESTATE -> "Real Estate";
            case GOLD, COMMODITY -> "Commodities";
            case CRYPTOCURRENCY -> "Crypto";
            case INSURANCE_POLICY -> "Insurance";
            default -> null;
        };
    }

    private Set<String> inferFactorExposures(List<Investment> investments, String userMessage) {
        Set<String> factors = new LinkedHashSet<>();
        for (Investment investment : investments) {
            String sector = investment.getSector() == null ? "" : investment.getSector().toLowerCase(Locale.ENGLISH);
            InvestmentType type = investment.getType();
            if (containsAny(sector, "bank", "nbfc", "realty", "housing")
                    || type == InvestmentType.BOND
                    || type == InvestmentType.FIXED_DEPOSIT) {
                factors.add("Rate Sensitive");
            }
            if (containsAny(sector, "capital market", "broker", "amc")) {
                factors.add("Market Liquidity");
            }
        }
        String lower = userMessage == null ? "" : userMessage.toLowerCase(Locale.ENGLISH);
        if (containsAny(lower, "rate", "yield", "repo", "home loan", "fd")) factors.add("Rate Sensitive");
        if (containsAny(lower, "liquidity", "volatility", "risk premium")) factors.add("Market Liquidity");
        if (containsAny(lower, "rupee", "currency", "fx", "dollar")) factors.add("FX Sensitive");
        return factors;
    }

    private Set<String> extractTaxTopics(String userMessage, List<FinancialGoal> goals) {
        Set<String> topics = new LinkedHashSet<>();
        String lower = userMessage == null ? "" : userMessage.toLowerCase(Locale.ENGLISH);
        if (containsAny(lower, "capital gain", "ltcg", "stcg")) topics.add("capital-gains");
        if (lower.contains("dividend")) topics.add("dividend-tax");
        if (containsAny(lower, "stt", "securities transaction tax")) topics.add("stt");
        if (containsAny(lower, "tds", "tax deducted")) topics.add("tds");
        if (goals.stream().anyMatch(goal -> goal.getType() != null)) {
            topics.add("capital-gains");
        }
        return topics;
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public List<PolicyDocumentSummary> listRecentDocuments(PolicyAuthority authority,
                                                           PolicyDocumentStatus status,
                                                           int limit) {
        List<PolicyDocument> documents = documentRepository.findRecentDocuments(authority, status);
        return toDocumentSummaries(limit(documents, limit));
    }

    @Transactional(readOnly = true)
    public Optional<PolicyDocumentSummary> getDocumentSummary(String documentKey) {
        return documentRepository.findByDocumentKey(documentKey).map(this::toDocumentSummary);
    }

    private void syncImpacts(PolicyDocument document,
                             PolicyDocumentVersion version,
                             List<PolicyImpactDraft> impactDrafts) {
        if (impactDrafts == null || impactDrafts.isEmpty()) {
            return;
        }

        for (PolicyImpactDraft draft : impactDrafts) {
            impactRepository.save(PolicyImpact.builder()
                    .document(document)
                    .version(version)
                    .policyArea(draft.policyArea() != null ? draft.policyArea() : document.getPolicyArea())
                    .subjectType(draft.subjectType())
                    .subjectKey(draft.subjectKey())
                    .subjectLabel(draft.subjectLabel())
                    .actionType(draft.actionType())
                    .transmissionChannel(draft.transmissionChannel())
                    .affectedParty(draft.affectedParty())
                    .direction(draft.direction())
                    .horizon(draft.horizon())
                    .effectiveFrom(draft.effectiveFrom() != null ? draft.effectiveFrom() : document.getEffectiveFrom())
                    .effectiveTo(draft.effectiveTo() != null ? draft.effectiveTo() : document.getEffectiveTo())
                    .implementationSummary(draft.implementationSummary())
                    .surpriseClassification(draft.surpriseClassification())
                    .legalForceRank(draft.legalForceRank() != null
                            ? draft.legalForceRank()
                            : legalForceRank(document.getBindingLevel(), document.getDocumentType()))
                    .marketMovingPower(draft.marketMovingPower() != null
                            ? draft.marketMovingPower()
                            : marketMovingPower(document.getAuthority(), document.getDocumentType(), draft.actionType()))
                    .confidenceScore(draft.confidenceScore())
                    .impactSummary(draft.impactSummary())
                    .reasoningNote(draft.reasoningNote())
                    .falsificationSignal(draft.falsificationSignal())
                    .tagsCsv(toCsv(draft.tags()))
                    .build());
        }
    }

    private List<PolicyDocumentSummary> toDocumentSummaries(List<PolicyDocument> documents) {
        return documents.stream().map(this::toDocumentSummary).toList();
    }

    private PolicyDocumentSummary toDocumentSummary(PolicyDocument document) {
        return new PolicyDocumentSummary(
                document.getId(),
                document.getDocumentKey(),
                document.getAuthority(),
                document.getDocumentType(),
                document.getBindingLevel(),
                document.getPolicyArea(),
                document.getStatus(),
                document.getTitle(),
                document.getSourceUrl(),
                document.getSourceReference(),
                document.getLatestVersionNumber(),
                document.getPublishedDate(),
                document.getEffectiveFrom(),
                document.getEffectiveTo(),
                splitCsv(document.getTagsCsv()),
                splitCsv(document.getAffectedSectorsCsv()),
                document.getSummary(),
                document.getUpdatedAt()
        );
    }

    private List<PolicyChunkMatch> toChunkMatches(List<PolicyChunk> chunks) {
        return chunks.stream().map(chunk -> {
            PolicyDocument document = chunk.getVersion().getDocument();
            return new PolicyChunkMatch(
                    chunk.getId(),
                    document.getId(),
                    document.getTitle(),
                    document.getAuthority(),
                    chunk.getChunkIndex(),
                    chunk.getHeading(),
                    chunk.getSectionPath(),
                    chunk.getCitationLabel(),
                    chunk.getPageNumberStart(),
                    chunk.getPageNumberEnd(),
                    chunk.getContent()
            );
        }).toList();
    }

    private List<PolicyEventCard> toPolicyEventCards(List<PolicyImpact> impacts) {
        return impacts.stream()
                .sorted(policyEventRankComparator())
                .map(this::toPolicyEventCard)
                .toList();
    }

    private PolicyEventCard toPolicyEventCard(PolicyImpact impact) {
        PolicyDocument document = impact.getDocument();
        PolicyEventCard.PolicyCitation citation = new PolicyEventCard.PolicyCitation(
                document.getAuthority(),
                document.getSourceReference(),
                document.getTitle(),
                document.getSourceUrl(),
                document.getPublishedDate(),
                impact.getEffectiveFrom() != null ? impact.getEffectiveFrom() : document.getEffectiveFrom()
        );

        return new PolicyEventCard(
                "policy-impact-" + impact.getId(),
                impact.getId(),
                document.getId(),
                document.getTitle(),
                document.getAuthority(),
                document.getDocumentType(),
                document.getBindingLevel(),
                impact.getPolicyArea(),
                document.getSourceReference(),
                document.getSourceUrl(),
                document.getPublishedDate(),
                impact.getEffectiveFrom(),
                impact.getEffectiveTo(),
                impact.getActionType(),
                impact.getSubjectType(),
                impact.getSubjectKey(),
                impact.getSubjectLabel(),
                impact.getAffectedParty(),
                impact.getTransmissionChannel(),
                impact.getDirection(),
                impact.getHorizon(),
                impact.getSurpriseClassification(),
                impact.getLegalForceRank(),
                impact.getMarketMovingPower(),
                impact.getConfidenceScore(),
                impact.getImpactSummary(),
                impact.getImplementationSummary(),
                impact.getReasoningNote(),
                impact.getFalsificationSignal(),
                citation,
                splitCsv(impact.getTagsCsv())
        );
    }

    private Comparator<PolicyImpact> policyEventRankComparator() {
        return Comparator.<PolicyImpact>comparingInt(impact -> nullableInt(impact.getMarketMovingPower())).reversed()
                .thenComparing(Comparator.<PolicyImpact>comparingInt(impact -> nullableInt(impact.getLegalForceRank())).reversed())
                .thenComparing(Comparator.<PolicyImpact>comparingDouble(impact -> nullableDouble(impact.getConfidenceScore())).reversed())
                .thenComparing(Comparator.comparing(
                        (PolicyImpact impact) -> nullableDate(impact.getDocument().getPublishedDate())).reversed())
                .thenComparing(PolicyImpact::getId, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private int nullableInt(Integer value) {
        return value != null ? value : 0;
    }

    private double nullableDouble(Double value) {
        return value != null ? value : 0.0;
    }

    private LocalDate nullableDate(LocalDate value) {
        return value != null ? value : LocalDate.MIN;
    }

    private int legalForceRank(PolicyBindingLevel bindingLevel, PolicyDocumentType documentType) {
        if (bindingLevel == PolicyBindingLevel.LAW || documentType == PolicyDocumentType.ACT) return 100;
        if (bindingLevel == PolicyBindingLevel.RULE || documentType == PolicyDocumentType.RULE) return 90;
        if (bindingLevel == PolicyBindingLevel.REGULATION) return 85;
        if (bindingLevel == PolicyBindingLevel.CIRCULAR_NOTIFICATION
                || bindingLevel == PolicyBindingLevel.BINDING_COMPLIANCE_CHANGE
                || documentType == PolicyDocumentType.CIRCULAR
                || documentType == PolicyDocumentType.NOTIFICATION
                || documentType == PolicyDocumentType.MASTER_DIRECTION) return 80;
        if (bindingLevel == PolicyBindingLevel.BINDING) return 75;
        if (bindingLevel == PolicyBindingLevel.MARKET_MOVING_GUIDANCE
                || bindingLevel == PolicyBindingLevel.AUTHORITATIVE_GUIDANCE) return 55;
        if (bindingLevel == PolicyBindingLevel.ADVISORY) return 35;
        if (bindingLevel == PolicyBindingLevel.INDUSTRY_BODY) return 25;
        return 15;
    }

    private int marketMovingPower(PolicyAuthority authority,
                                  PolicyDocumentType documentType,
                                  PolicyActionType actionType) {
        if (authority == PolicyAuthority.RBI
                && (documentType == PolicyDocumentType.MONETARY_POLICY
                || actionType == PolicyActionType.RATE_CHANGE
                || actionType == PolicyActionType.LIQUIDITY_OPERATION)) return 95;
        if (authority == PolicyAuthority.SEBI
                && (documentType == PolicyDocumentType.CIRCULAR
                || actionType == PolicyActionType.MARKET_STRUCTURE_CHANGE)) return 80;
        if (actionType == PolicyActionType.TAX_RATE_CHANGE || actionType == PolicyActionType.TDS_CHANGE) return 85;
        if (documentType == PolicyDocumentType.PRESS_RELEASE || documentType == PolicyDocumentType.ANNOUNCEMENT) return 45;
        return 60;
    }

    private Set<String> extractGoalTerms(List<FinancialGoal> goals) {
        Set<String> terms = new LinkedHashSet<>();
        for (FinancialGoal goal : goals) {
            switch (goal.getType()) {
                case null -> {
                    continue;
                }
                case RETIREMENT, INVESTMENT, WEALTH_BUILDING -> {
                    terms.add("capital markets");
                    terms.add("tax");
                }
                case PROPERTY -> {
                    terms.add("housing");
                    terms.add("home loan");
                }
                case EMERGENCY_FUND, SAVINGS -> {
                    terms.add("interest rate");
                    terms.add("monetary policy");
                }
                default -> {
                }
            }
        }
        return terms;
    }

    private Set<String> extractMessageTerms(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Set.of();
        }

        String lower = userMessage.toLowerCase(Locale.ENGLISH);
        Set<String> terms = new LinkedHashSet<>();
        if (lower.contains("rbi")) terms.add("rbi");
        if (lower.contains("sebi")) terms.add("sebi");
        if (lower.contains("tax")) terms.add("tax");
        if (lower.contains("monetary")) terms.add("monetary policy");
        if (lower.contains("scheme")) terms.add("scheme");
        if (lower.contains("budget")) terms.add("budget");
        if (lower.contains("capital gain")) terms.add("capital gain");
        return terms;
    }

    private List<String> limit(Collection<String> values, int limit) {
        return values.stream().limit(limit).toList();
    }

    private <T> List<T> limit(List<T> values, int limit) {
        return values.stream().limit(limit).toList();
    }

    private String normalize(String rawText) {
        return rawText == null ? "" : rawText
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String toCsv(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private String resolveDocumentKey(PolicyIngestionRequest request) {
        if (request.documentKey() != null && !request.documentKey().isBlank()) {
            return request.documentKey().trim().toLowerCase(Locale.ENGLISH);
        }

        String authority = request.authority() != null
                ? request.authority().name().toLowerCase(Locale.ENGLISH)
                : "unknown";
        String slug = request.title().trim().toLowerCase(Locale.ENGLISH)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return authority + ":" + slug;
    }

    private Optional<PolicyDocument> resolveExistingDocument(PolicyIngestionRequest request, String documentKey) {
        if (request.sourceReference() != null && !request.sourceReference().isBlank()) {
            Optional<PolicyDocument> bySourceReference =
                    documentRepository.findBySourceReferenceIgnoreCase(request.sourceReference().trim());
            if (bySourceReference.isPresent()) {
                return bySourceReference;
            }
        }

        if (request.externalDocumentId() != null && !request.externalDocumentId().isBlank()) {
            Optional<PolicyDocument> byExternalId =
                    documentRepository.findByExternalDocumentIdIgnoreCase(request.externalDocumentId().trim());
            if (byExternalId.isPresent()) {
                return byExternalId;
            }
        }

        if (request.sourceUrl() != null && !request.sourceUrl().isBlank()) {
            Optional<PolicyDocument> bySourceUrl =
                    documentRepository.findBySourceUrlIgnoreCase(request.sourceUrl().trim());
            if (bySourceUrl.isPresent()) {
                return bySourceUrl;
            }
        }

        return documentRepository.findByDocumentKey(documentKey);
    }

    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toList();
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash policy content", e);
        }
    }

    public record PolicyIngestionRequest(
            String documentKey,
            PolicyAuthority authority,
            PolicyDocumentType documentType,
            PolicyBindingLevel bindingLevel,
            PolicyArea policyArea,
            PolicyDocumentStatus status,
            String title,
            String sourceUrl,
            String sourceReference,
            String externalDocumentId,
            LocalDate publishedDate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            LocalDateTime issuedAt,
            String versionLabel,
            String supersedesReference,
            String summary,
            List<String> tags,
            List<String> affectedSectors,
            String metadataJson,
            String rawText,
            List<PolicyImpactDraft> impacts
    ) {
    }

    public record PolicyImpactDraft(
            PolicyArea policyArea,
            PolicySubjectType subjectType,
            String subjectKey,
            String subjectLabel,
            PolicyActionType actionType,
            PolicyTransmissionChannel transmissionChannel,
            String affectedParty,
            PolicyImpactDirection direction,
            PolicyImpactHorizon horizon,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String implementationSummary,
            PolicySurpriseClassification surpriseClassification,
            Integer legalForceRank,
            Integer marketMovingPower,
            Double confidenceScore,
            String impactSummary,
            String reasoningNote,
            String falsificationSignal,
            List<String> tags
    ) {
        public PolicyImpactDraft(PolicyArea policyArea,
                                 PolicySubjectType subjectType,
                                 String subjectKey,
                                 String subjectLabel,
                                 PolicyImpactDirection direction,
                                 PolicyImpactHorizon horizon,
                                 LocalDate effectiveFrom,
                                 LocalDate effectiveTo,
                                 Double confidenceScore,
                                 String impactSummary,
                                 String reasoningNote,
                                 List<String> tags) {
            this(policyArea, subjectType, subjectKey, subjectLabel,
                    null, null, null,
                    direction, horizon, effectiveFrom, effectiveTo,
                    null, null, null, null,
                    confidenceScore, impactSummary, reasoningNote, null, tags);
        }
    }

    public record PolicySearchResult(
            List<PolicyDocumentSummary> documents,
            List<PolicyChunkMatch> chunks,
            List<PolicyEventCard> eventCards
    ) {
        public List<PolicyEventCard> impacts() {
            return eventCards;
        }
    }

    public record AdvisorPolicyContext(
            List<PolicyDocumentSummary> documents,
            List<PolicyEventCard> eventCards,
            List<PolicyChunkMatch> chunks
    ) {
        public List<PolicyEventCard> impacts() {
            return eventCards;
        }
    }

    public record PolicyDocumentSummary(
            Long id,
            String documentKey,
            PolicyAuthority authority,
            PolicyDocumentType documentType,
            PolicyBindingLevel bindingLevel,
            PolicyArea policyArea,
            PolicyDocumentStatus status,
            String title,
            String sourceUrl,
            String sourceReference,
            Integer latestVersionNumber,
            LocalDate publishedDate,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<String> tags,
            List<String> affectedSectors,
            String summary,
            LocalDateTime updatedAt
    ) {
    }

    public record PolicyChunkMatch(
            Long chunkId,
            Long documentId,
            String documentTitle,
            PolicyAuthority authority,
            Integer chunkIndex,
            String heading,
            String sectionPath,
            String citationLabel,
            Integer pageNumberStart,
            Integer pageNumberEnd,
            String content
    ) {
    }
}
