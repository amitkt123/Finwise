package org.amit.finwise.admin.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.admin.service.AdminPolicyService;
import org.amit.finwise.policy.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/policy")
@RequiredArgsConstructor
public class AdminPolicyController {

    private final AdminPolicyService adminPolicyService;

    @PatchMapping("/documents/{id}/status")
    public PolicyDocument updateStatus(@PathVariable Long id, @RequestBody StatusRequest body) {
        return adminPolicyService.updateStatus(id, body.status());
    }

    @PatchMapping("/documents/{id}/metadata")
    public PolicyDocument updateMetadata(@PathVariable Long id, @RequestBody MetadataRequest body) {
        return adminPolicyService.updateMetadata(id, body.effectiveTo(), body.tags());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable Long id) {
        adminPolicyService.deleteDocument(id);
    }

    @GetMapping("/documents/{id}/chunks")
    public List<PolicyChunk> getChunks(@PathVariable Long id) {
        return adminPolicyService.getChunks(id);
    }

    @GetMapping("/documents/{documentId}/impacts")
    public List<PolicyImpact> getImpacts(@PathVariable Long documentId) {
        return adminPolicyService.getImpacts(documentId);
    }

    @PatchMapping("/impacts/{id}")
    public PolicyImpact updateImpact(@PathVariable Long id, @RequestBody ImpactPatchRequest body) {
        return adminPolicyService.updateImpact(id, body.direction(), body.confidenceScore(), body.horizon());
    }

    @PostMapping("/documents/upload-pdf")
    public PolicyDocument uploadPdf(
            @RequestPart("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam PolicyAuthority authority,
            @RequestParam PolicyDocumentType documentType,
            @RequestParam PolicyBindingLevel bindingLevel,
            @RequestParam PolicyArea policyArea,
            @RequestParam(required = false) String sourceUrl,
            @RequestParam(required = false) String summary) {
        return adminPolicyService.uploadPdf(file, title, authority, documentType, bindingLevel, policyArea, sourceUrl, summary);
    }

    record StatusRequest(PolicyDocumentStatus status) {}
    record MetadataRequest(LocalDate effectiveTo, List<String> tags) {}
    record ImpactPatchRequest(PolicyImpactDirection direction, Double confidenceScore, PolicyImpactHorizon horizon) {}
}
