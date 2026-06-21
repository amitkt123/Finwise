package org.amit.finwise.admin.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.document.service.PdfTextExtractionService;
import org.amit.finwise.policy.model.*;
import org.amit.finwise.policy.repository.*;
import org.amit.finwise.policy.service.PolicyIntelligenceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminPolicyService {

    private final PolicyDocumentRepository documentRepository;
    private final PolicyDocumentVersionRepository versionRepository;
    private final PolicyChunkRepository chunkRepository;
    private final PolicyImpactRepository impactRepository;
    private final PolicyIntelligenceService policyIntelligenceService;
    private final PdfTextExtractionService pdfTextExtractionService;

    @Transactional
    public PolicyDocument updateStatus(Long id, PolicyDocumentStatus status) {
        PolicyDocument doc = findDocOrThrow(id);
        doc.setStatus(status);
        return documentRepository.save(doc);
    }

    @Transactional
    public PolicyDocument updateMetadata(Long id, LocalDate effectiveTo, List<String> tags) {
        PolicyDocument doc = findDocOrThrow(id);
        if (effectiveTo != null) doc.setEffectiveTo(effectiveTo);
        if (tags != null) doc.setTagsCsv(String.join(",", tags));
        return documentRepository.save(doc);
    }

    @Transactional
    public void deleteDocument(Long id) {
        PolicyDocument doc = findDocOrThrow(id);
        List<PolicyDocumentVersion> versions = versionRepository.findByDocumentOrderByVersionNumberDesc(doc);
        for (PolicyDocumentVersion v : versions) {
            chunkRepository.deleteByVersion(v);
            impactRepository.deleteByVersion(v);
        }
        impactRepository.deleteByDocument(doc);
        versionRepository.deleteAll(versions);
        documentRepository.delete(doc);
    }

    @Transactional(readOnly = true)
    public List<PolicyChunk> getChunks(Long documentId) {
        PolicyDocument doc = findDocOrThrow(documentId);
        PolicyDocumentVersion version = versionRepository.findByDocumentAndCurrentTrue(doc)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No current version for document " + documentId));
        return chunkRepository.findByVersionOrderByChunkIndexAsc(version);
    }

    @Transactional(readOnly = true)
    public List<PolicyImpact> getImpacts(Long documentId) {
        PolicyDocument doc = findDocOrThrow(documentId);
        return impactRepository.findByDocumentOrderByCreatedAtDesc(doc);
    }

    @Transactional
    public PolicyImpact updateImpact(Long impactId, PolicyImpactDirection direction,
                                      Double confidenceScore, PolicyImpactHorizon horizon) {
        PolicyImpact impact = impactRepository.findById(impactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Impact not found: " + impactId));
        if (direction != null) impact.setDirection(direction);
        if (confidenceScore != null) impact.setConfidenceScore(confidenceScore);
        if (horizon != null) impact.setHorizon(horizon);
        return impactRepository.save(impact);
    }

    @Transactional
    public PolicyDocument uploadPdf(MultipartFile file, String title, PolicyAuthority authority,
                                     PolicyDocumentType documentType, PolicyBindingLevel bindingLevel,
                                     PolicyArea policyArea, String sourceUrl, String summary) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }
        String rawText = pdfTextExtractionService.extract(bytes, null);
        if (rawText == null || rawText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not extract text from PDF (possibly password-protected)");
        }
        PolicyIntelligenceService.PolicyIngestionRequest request = new PolicyIntelligenceService.PolicyIngestionRequest(
                null, authority, documentType, bindingLevel, policyArea,
                PolicyDocumentStatus.ACTIVE, title, sourceUrl, null, null,
                null, null, null, null, null, null, summary,
                null, null, null, rawText, null
        );
        return policyIntelligenceService.ingestDocument(request);
    }

    private PolicyDocument findDocOrThrow(Long id) {
        return documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Policy document not found: " + id));
    }
}
