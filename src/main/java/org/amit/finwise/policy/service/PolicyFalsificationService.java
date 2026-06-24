package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.policy.model.PolicyFalsificationRecord;
import org.amit.finwise.policy.model.PolicyImpact;
import org.amit.finwise.policy.model.PolicyImpactDirection;
import org.amit.finwise.policy.model.PolicySubjectType;
import org.amit.finwise.policy.repository.PolicyFalsificationRepository;
import org.amit.finwise.policy.repository.PolicyImpactRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Phase 3.3 — Falsification monitoring + calibration.
 *
 * After a policy's effective date, this service checks whether the predicted
 * market reaction ({@link PolicyImpact#getDirection()}) actually occurred in
 * sector/index market data. It stores the result in
 * {@link PolicyFalsificationRecord} and updates the impact's confidence score
 * via a simple shrinkage adjustment (mirroring ConfidenceCalibrationService).
 *
 * Runs on a nightly schedule from {@link org.amit.finwise.policy.scheduler.PolicyIntelligenceScheduler}.
 * Idempotent: impacts with an existing falsification record are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyFalsificationService {

    private static final int HORIZON_DAYS = 5;
    private static final double SHRINKAGE_K = 10.0;

    private final PolicyFalsificationRepository falsificationRepository;
    private final PolicyImpactRepository impactRepository;
    private final IndexEodRepository indexEodRepository;
    private final FactorProperties factorProperties;

    @Transactional
    public int checkPendingImpacts() {
        LocalDate cutoff = LocalDate.now().minusDays(HORIZON_DAYS + 1);
        List<PolicyImpact> pending = falsificationRepository.findImpactsPendingFalsification(cutoff);

        int checked = 0;
        for (PolicyImpact impact : pending) {
            try {
                checkImpact(impact);
                checked++;
            } catch (Exception e) {
                log.warn("[PolicyFalsification] Failed to check impact {}: {}", impact.getId(), e.getMessage());
            }
        }
        log.info("[PolicyFalsification] Checked {} pending impacts", checked);
        return checked;
    }

    private void checkImpact(PolicyImpact impact) {
        LocalDate effectiveDate = impact.getEffectiveFrom();
        if (effectiveDate == null) return;

        String indexSymbol = resolveIndexSymbol(impact);
        if (indexSymbol == null) return;

        LocalDate windowEnd = effectiveDate.plusDays(HORIZON_DAYS + 3);
        List<IndexEod> indexSeries = indexEodRepository
                .findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(indexSymbol, effectiveDate, windowEnd);

        List<IndexEod> niftySeries = indexEodRepository
                .findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate("NIFTY 50", effectiveDate, windowEnd);

        if (indexSeries.size() < 2 || niftySeries.size() < 2) {
            log.debug("[PolicyFalsification] Not enough data for impact {} (indexSeries={}, niftySeries={})",
                    impact.getId(), indexSeries.size(), niftySeries.size());
            return;
        }

        double sectorReturn = computeReturn(indexSeries);
        double niftyReturn = computeReturn(niftySeries);
        double excessReturn = sectorReturn - niftyReturn;

        boolean confirmed = isConfirmed(impact.getDirection(), sectorReturn);
        double updatedConfidence = shrink(impact.getConfidenceScore(), confirmed);

        falsificationRepository.save(PolicyFalsificationRecord.builder()
                .impact(impact)
                .subjectKey(impact.getSubjectKey())
                .effectiveDate(effectiveDate)
                .horizonDays(HORIZON_DAYS)
                .predictedDirection(impact.getDirection())
                .realizedReturn(sectorReturn)
                .excessReturnVsNifty(excessReturn)
                .directionConfirmed(confirmed)
                .updatedConfidence(updatedConfidence)
                .notes("Sector index: " + indexSymbol + "; realized=" + String.format("%.2f%%", sectorReturn * 100))
                .build());

        impact.setConfidenceScore(updatedConfidence);
        impactRepository.save(impact);

        log.debug("[PolicyFalsification] impact={} confirmed={} sectorReturn={}% excess={}%",
                impact.getId(), confirmed,
                String.format("%.2f", sectorReturn * 100), String.format("%.2f", excessReturn * 100));
    }

    private String resolveIndexSymbol(PolicyImpact impact) {
        if (impact.getSubjectType() == PolicySubjectType.SECTOR && impact.getSubjectKey() != null) {
            Optional<String> idx = factorProperties.indexForSector(impact.getSubjectKey());
            if (idx.isPresent()) return idx.get();
        }
        return "NIFTY 50";
    }

    private double computeReturn(List<IndexEod> series) {
        if (series.size() < 2) return 0.0;
        double open = series.getFirst().getClose().doubleValue();
        double close = series.getLast().getClose().doubleValue();
        return open == 0 ? 0.0 : (close - open) / open;
    }

    private boolean isConfirmed(PolicyImpactDirection direction, double sectorReturn) {
        if (direction == null) return false;
        return switch (direction) {
            case POSITIVE -> sectorReturn > 0;
            case NEGATIVE -> sectorReturn < 0;
            default -> Math.abs(sectorReturn) < 0.005;
        };
    }

    private double shrink(Double rawConfidence, boolean confirmed) {
        Double hitRate = confirmed ? 1.0 : 0.0;
        double raw = rawConfidence != null ? Math.max(0.0, Math.min(1.0, rawConfidence)) : 0.6;
        return (1 * hitRate + SHRINKAGE_K * raw) / (1 + SHRINKAGE_K);
    }
}
