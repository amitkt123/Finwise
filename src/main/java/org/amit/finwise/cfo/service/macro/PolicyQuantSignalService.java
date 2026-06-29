package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry.SignalStatus;
import org.amit.finwise.cfo.repository.macro.PolicyQuantSignalRepository;
import org.amit.finwise.policy.model.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Routes policy event cards from all 5 transmission channels into the
 * {@link PolicyQuantSignalQueueEntry} queue, auto-approving high-confidence
 * signals from trusted authorities and queuing everything else for admin review.
 */
@Service
@Slf4j
public class PolicyQuantSignalService {

    private static final double AUTO_APPROVE_THRESHOLD = 0.75;

    private final PolicyQuantSignalRepository repo;
    private final QuantitativeMacroState macroState;

    public PolicyQuantSignalService(PolicyQuantSignalRepository repo,
                                    QuantitativeMacroState macroState) {
        this.repo = repo;
        this.macroState = macroState;
    }

    /**
     * Process a batch of {@link PolicyEventCard}s from a policy intelligence run.
     * Trusted-authority cards across all 5 channels are extracted for quant signals;
     * high-confidence extractions are auto-approved, the rest queued as PENDING.
     */
    public void process(List<PolicyEventCard> cards) {
        for (PolicyEventCard card : cards) {
            boolean trustedAuthority = isTrustedAuthority(card);

            SignalExtraction signal = trustedAuthority ? extractSignal(card) : null;
            double extractedValue = signal != null ? signal.value() : Double.NaN;
            double confidence = computeConfidence(card, extractedValue);

            SignalStatus status = (signal != null && confidence >= AUTO_APPROVE_THRESHOLD)
                    ? SignalStatus.AUTO_APPROVE
                    : SignalStatus.PENDING;

            String paramKey = signal != null ? signal.paramKey() : "riskFreeRate";

            PolicyQuantSignalQueueEntry entry = PolicyQuantSignalQueueEntry.builder()
                    .sourceEventCardId(card.impactId())
                    .parameterKey(paramKey)
                    .proposedValue(Double.isNaN(extractedValue) ? 0.0 : extractedValue)
                    .currentValue(macroState.getRiskFreeRate())
                    .confidence(confidence)
                    .status(status)
                    .build();
            repo.save(entry);

            if (status == SignalStatus.AUTO_APPROVE) {
                if ("riskFreeRate".equals(paramKey)) {
                    macroState.setRiskFreeRate(extractedValue, "AUTO");
                    log.info("[PolicyQuant] Auto-approved riskFreeRate={} confidence={} source={}",
                            extractedValue, confidence, card.authority());
                } else {
                    macroState.putPolicyRateShock(paramKey, extractedValue);
                    log.info("[PolicyQuant] Auto-approved shock key={} value={} confidence={} source={}",
                            paramKey, extractedValue, confidence, card.authority());
                }
            } else {
                log.debug("[PolicyQuant] Queued PENDING signal authority={} channel={} confidence={}",
                        card.authority(), card.transmissionChannel(), confidence);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private record SignalExtraction(String paramKey, double value) {}

    private SignalExtraction extractSignal(PolicyEventCard card) {
        String surprise = normalizeSurprise(card.surpriseClassification());
        return switch (card.transmissionChannel()) {
            case DISCOUNT_RATE, CREDIT_COST -> {
                double v = extractPctFromText(card.documentTitle());
                yield Double.isNaN(v) ? null : new SignalExtraction("riskFreeRate", v / 100.0);
            }
            case SECTOR_MARGIN -> {
                double v = extractPctFromText(card.documentTitle());
                yield Double.isNaN(v) ? null
                        : new SignalExtraction("SIZE:" + surprise, -v / 100.0);
            }
            case LIQUIDITY_RULE -> {
                double v = extractBpsFromText(card.documentTitle());
                yield Double.isNaN(v) ? null
                        : new SignalExtraction("BANKING:" + surprise, -(v / 10000.0) * 15.0);
            }
            case FISCAL_STIMULUS -> {
                double v = extractPctFromText(card.documentTitle());
                yield Double.isNaN(v) ? null
                        : new SignalExtraction("MKT:" + surprise, v / 100.0 * 0.3);
            }
            case FII_REGULATORY -> {
                boolean outflow = card.documentTitle() != null && (
                        card.documentTitle().toLowerCase().contains("restrict")
                        || card.documentTitle().toLowerCase().contains("curb"));
                yield new SignalExtraction("FII_FLOW:" + surprise, outflow ? -0.021 : 0.015);
            }
            default -> null;
        };
    }

    /** Maps PolicySurpriseClassification to the HIGH_SURPRISE / LOW_SURPRISE strings
     *  consumed by StressScenarioService for scaling. */
    private String normalizeSurprise(PolicySurpriseClassification s) {
        if (s == null) return "";
        return switch (s) {
            case HAWKISH_SURPRISE, STRICTER_THAN_EXPECTED -> "HIGH_SURPRISE";
            case DOVISH_SURPRISE, EASIER_THAN_EXPECTED -> "LOW_SURPRISE";
            default -> s.name();
        };
    }

    private double extractPctFromText(String text) {
        if (text == null) return Double.NaN;
        var m = Pattern.compile("(\\d+\\.?\\d*)\\s*(?:%|per\\s*cent)", Pattern.CASE_INSENSITIVE)
                .matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private double extractBpsFromText(String text) {
        if (text == null) return Double.NaN;
        var m = Pattern.compile("(\\d+)\\s*bps").matcher(text.toLowerCase());
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private boolean isTrustedAuthority(PolicyEventCard card) {
        return card.authority() == PolicyAuthority.RBI
                || card.authority() == PolicyAuthority.SEBI
                || card.authority() == PolicyAuthority.MINISTRY_OF_FINANCE
                || card.authority() == PolicyAuthority.CBDT;
    }

    private double computeConfidence(PolicyEventCard card, double extractedValue) {
        double authorityWeight = switch (card.authority()) {
            case RBI -> 1.0;
            case SEBI -> 0.95;
            case MINISTRY_OF_FINANCE, CBDT -> 0.85;
            default -> 0.60;
        };
        double specificity = Double.isNaN(extractedValue) ? 0.4 : 1.0;
        return authorityWeight * specificity;
    }
}
