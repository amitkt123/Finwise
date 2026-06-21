package org.amit.finwise.admin.service;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.auth.UserRepository;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.document.model.DocumentUpload;
import org.amit.finwise.document.repository.DocumentUploadRepository;
import org.amit.finwise.marketdata.ops.DataQualityService;
import org.amit.finwise.policy.model.PolicyDocumentStatus;
import org.amit.finwise.policy.repository.PolicyDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private final UserRepository userRepository;
    private final PolicyDocumentRepository policyDocumentRepository;
    private final DocumentUploadRepository documentUploadRepository;
    private final DataQualityService dataQualityService;
    private final MacroSeriesService macroSeriesService;

    public record AdminOverview(
            long totalUsers,
            long enabledUsers,
            long totalPolicyDocuments,
            long activePolicyDocuments,
            long totalDocumentUploads,
            long failedDocumentUploads,
            Map<String, Object> dataQuality,
            Map<String, Object> macroFreshness,
            List<Object> schedulerJobs
    ) {}

    public AdminOverview overview() {
        return new AdminOverview(
                userRepository.count(),
                userRepository.countByEnabledTrue(),
                policyDocumentRepository.count(),
                policyDocumentRepository.countByStatus(PolicyDocumentStatus.ACTIVE),
                documentUploadRepository.count(),
                documentUploadRepository.countByParseStatus(DocumentUpload.ParseStatus.FAILED),
                dataQualityService.report(),
                macroSeriesService.freshnessView(),
                List.of()
        );
    }
}
