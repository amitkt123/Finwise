package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.policy.model.PolicyDocument;
import org.amit.finwise.policy.model.PolicyImpact;
import org.amit.finwise.policy.model.PolicyImpactDirection;
import org.amit.finwise.policy.model.PolicySubjectType;
import org.amit.finwise.policy.repository.PolicyDocumentRepository;
import org.amit.finwise.policy.repository.PolicyImpactRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 3.2 — Event-driven notifications.
 *
 * Accepts IDs (not detached entities) so it is safe to run async after the
 * ingest transaction commits. Each repository call is self-transactional.
 * Sends an alert email when a new high-impact policy affects a user's portfolio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyNotificationService {

    private final InvestmentRepository investmentRepository;
    private final EmailNotificationService emailNotificationService;
    private final PolicyDocumentRepository documentRepository;
    private final PolicyImpactRepository impactRepository;

    @Value("${policy.notification.min-market-power:70}")
    private int minMarketPower;

    @Value("${cfo.user.email:}")
    private String userEmail;

    @Async("llmRefinementExecutor")
    public void notifyAsync(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) return;

        Optional<PolicyDocument> docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) return;
        PolicyDocument document = docOpt.get();

        List<PolicyImpact> impacts = impactRepository.findByVersionId(versionId);
        if (impacts.isEmpty()) return;

        List<PolicyImpact> highImpact = impacts.stream()
                .filter(i -> i.getMarketMovingPower() != null && i.getMarketMovingPower() >= minMarketPower)
                .toList();

        if (highImpact.isEmpty()) return;

        List<String> userIds = investmentRepository.findDistinctActiveUserIds();
        if (userIds.isEmpty()) {
            sendAlertIfMatch(document, highImpact, Set.of(), Set.of(), userEmail);
            return;
        }

        for (String userId : userIds) {
            List<Investment> investments = investmentRepository.findActiveInvestments(userId);
            Set<String> userSectors = investments.stream()
                    .map(Investment::getSector)
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase(Locale.ENGLISH))
                    .collect(Collectors.toSet());
            Set<String> userAssetClasses = investments.stream()
                    .map(Investment::getType)
                    .filter(Objects::nonNull)
                    .map(t -> t.name().toLowerCase(Locale.ENGLISH))
                    .collect(Collectors.toSet());

            sendAlertIfMatch(document, highImpact, userSectors, userAssetClasses, resolveEmail(userId));
        }
    }

    private void sendAlertIfMatch(PolicyDocument document,
                                   List<PolicyImpact> highImpact,
                                   Set<String> userSectors,
                                   Set<String> userAssetClasses,
                                   String email) {
        if (email == null || email.isBlank()) return;

        List<PolicyImpact> matched = highImpact.stream()
                .filter(i -> isRelevant(i, userSectors, userAssetClasses))
                .toList();

        if (matched.isEmpty()) return;

        emailNotificationService.sendPortfolioAlert(
                "Policy Alert: " + document.getTitle(),
                buildAlertBody(document, matched),
                email);

        log.info("[PolicyNotification] Alert sent for doc={}, {} matched impacts",
                document.getDocumentKey(), matched.size());
    }

    private boolean isRelevant(PolicyImpact impact, Set<String> userSectors, Set<String> userAssetClasses) {
        PolicySubjectType type = impact.getSubjectType();
        if (type == null) return true;
        String key = impact.getSubjectKey() == null ? "" : impact.getSubjectKey().toLowerCase(Locale.ENGLISH);
        return switch (type) {
            case SECTOR -> userSectors.isEmpty() || userSectors.stream()
                    .anyMatch(s -> key.contains(s) || s.contains(key));
            case ASSET_CLASS -> userAssetClasses.isEmpty() || userAssetClasses.stream()
                    .anyMatch(a -> key.contains(a) || a.contains(key));
            case MARKET_STRUCTURE, TAX_TOPIC, THEME -> true;
            default -> false;
        };
    }

    private String buildAlertBody(PolicyDocument document, List<PolicyImpact> impacts) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Source:** ").append(document.getAuthority()).append("\n\n");
        if (document.getSourceReference() != null) {
            sb.append("**Reference:** ").append(document.getSourceReference()).append("\n\n");
        }
        sb.append("**Key Impacts:**\n\n");
        for (PolicyImpact impact : impacts) {
            String dir = impact.getDirection() == PolicyImpactDirection.POSITIVE ? "Positive"
                    : impact.getDirection() == PolicyImpactDirection.NEGATIVE ? "Negative" : "Mixed";
            sb.append("- **").append(impact.getSubjectLabel()).append("** (").append(dir).append("): ")
              .append(impact.getImpactSummary()).append("\n");
        }
        if (document.getSummary() != null) {
            sb.append("\n**Summary:** ").append(document.getSummary());
        }
        return sb.toString();
    }

    private String resolveEmail(String userId) {
        return userEmail;
    }
}
