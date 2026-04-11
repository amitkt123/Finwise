package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.goal.model.FinancialGoal;
import org.amit.finwise.goal.repository.FinancialGoalRepository;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.policy.model.*;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.amit.finwise.policy.repository.PolicyDocumentRepository;
import org.amit.finwise.policy.repository.PolicyDocumentVersionRepository;
import org.amit.finwise.policy.repository.PolicyImpactRepository;
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
        for (PolicyChunkingService.ChunkDraft draft : chunkDrafts) {
            chunkRepository.save(PolicyChunk.builder()
                    .version(version)
                    .chunkIndex(draft.chunkIndex())
                    .heading(draft.heading())
                    .sectionPath(draft.sectionPath())
                    .citationLabel(draft.citationLabel())
                    .content(draft.content())
                    .keywordText(draft.keywordText())
                    .build());
        }

        syncImpacts(document, version, request.impacts());
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
        List<PolicyChunk> chunks = limit(chunkRepository.searchCurrentChunks(trimmed), limit);
        List<PolicyImpact> impacts = limit(impactRepository.searchActiveImpacts(trimmed, LocalDate.now()), limit);
        return new PolicySearchResult(
                toDocumentSummaries(documents),
                toChunkMatches(chunks),
                toImpactMatches(impacts));
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

        Set<String> queryTerms = new LinkedHashSet<>(sectors);
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

        List<PolicyChunk> matchedChunks = new ArrayList<>();
        for (String term : queryTerms) {
            if (matchedChunks.size() >= limit) {
                break;
            }
            List<PolicyChunk> found = chunkRepository.searchCurrentChunks(term);
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

        return new AdvisorPolicyContext(
                toDocumentSummaries(documents),
                toImpactMatches(matchedImpacts),
                toChunkMatches(matchedChunks));
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
                    .direction(draft.direction())
                    .horizon(draft.horizon())
                    .effectiveFrom(draft.effectiveFrom() != null ? draft.effectiveFrom() : document.getEffectiveFrom())
                    .effectiveTo(draft.effectiveTo() != null ? draft.effectiveTo() : document.getEffectiveTo())
                    .confidenceScore(draft.confidenceScore())
                    .impactSummary(draft.impactSummary())
                    .reasoningNote(draft.reasoningNote())
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

    private List<PolicyImpactMatch> toImpactMatches(List<PolicyImpact> impacts) {
        return impacts.stream().map(impact -> new PolicyImpactMatch(
                impact.getId(),
                impact.getDocument().getId(),
                impact.getDocument().getTitle(),
                impact.getDocument().getAuthority(),
                impact.getPolicyArea(),
                impact.getSubjectType(),
                impact.getSubjectKey(),
                impact.getSubjectLabel(),
                impact.getDirection(),
                impact.getHorizon(),
                impact.getEffectiveFrom(),
                impact.getEffectiveTo(),
                impact.getConfidenceScore(),
                impact.getImpactSummary(),
                impact.getReasoningNote(),
                splitCsv(impact.getTagsCsv())
        )).toList();
    }

    private Set<String> extractGoalTerms(List<FinancialGoal> goals) {
        Set<String> terms = new LinkedHashSet<>();
        for (FinancialGoal goal : goals) {
            if (goal.getType() == null) {
                continue;
            }
            switch (goal.getType()) {
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
            PolicyImpactDirection direction,
            PolicyImpactHorizon horizon,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Double confidenceScore,
            String impactSummary,
            String reasoningNote,
            List<String> tags
    ) {
    }

    public record PolicySearchResult(
            List<PolicyDocumentSummary> documents,
            List<PolicyChunkMatch> chunks,
            List<PolicyImpactMatch> impacts
    ) {
    }

    public record AdvisorPolicyContext(
            List<PolicyDocumentSummary> documents,
            List<PolicyImpactMatch> impacts,
            List<PolicyChunkMatch> chunks
    ) {
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

    public record PolicyImpactMatch(
            Long impactId,
            Long documentId,
            String documentTitle,
            PolicyAuthority authority,
            PolicyArea policyArea,
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
            List<String> tags
    ) {
    }
}
