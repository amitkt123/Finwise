package org.amit.finwise.cfo.service.macro;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry;
import org.amit.finwise.cfo.model.macro.PolicyQuantSignalQueueEntry.SignalStatus;
import org.amit.finwise.cfo.repository.macro.PolicyQuantSignalRepository;
import org.amit.finwise.policy.model.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 1: routes policy event cards with rate-channel signals into the
 * {@link PolicyQuantSignalQueueEntry} queue, auto-approving high-confidence
 * RBI/SEBI/MoF rate changes and queuing everything else for admin review.
 */
@Service
@Slf4j
public class PolicyQuantSignalService {

    // Channels that carry direct interest-rate information
    private static final Set<PolicyTransmissionChannel> RATE_WHITELIST = Set.of(
            PolicyTransmissionChannel.DISCOUNT_RATE,
            PolicyTransmissionChannel.CREDIT_COST
    );

    private static final double AUTO_APPROVE_THRESHOLD = 0.75;

    // Matches "6.50%", "6.5 per cent", "6 per cent" etc.
    private static final Pattern RATE_PATTERN =
            Pattern.compile("(\\d+\\.?\\d*)\\s*(?:%|per\\s*cent)", Pattern.CASE_INSENSITIVE);

    private final PolicyQuantSignalRepository repo;
    private final QuantitativeMacroState macroState;

    public PolicyQuantSignalService(PolicyQuantSignalRepository repo,
                                    QuantitativeMacroState macroState) {
        this.repo = repo;
        this.macroState = macroState;
    }

    /**
     * Process a batch of {@link PolicyEventCard}s from a policy intelligence run.
     * <ul>
     *   <li>Cards from trusted authorities (RBI, SEBI, MoF) on the DISCOUNT_RATE / CREDIT_COST
     *       channel with an extractable rate value are auto-approved when confidence ≥ 0.75.</li>
     *   <li>All other cards (untrusted authority, non-rate channel, or no extractable value)
     *       are persisted as PENDING for admin review.</li>
     * </ul>
     */
    public void process(List<PolicyEventCard> cards) {
        for (PolicyEventCard card : cards) {
            boolean trustedAuthority = isTrustedAuthority(card);
            boolean rateChannel = RATE_WHITELIST.contains(card.transmissionChannel());

            double extractedValue = Double.NaN;
            if (trustedAuthority && rateChannel) {
                extractedValue = extractRateValue(card);
            }

            double confidence = computeConfidence(card, extractedValue);
            SignalStatus status;
            if (!Double.isNaN(extractedValue) && confidence >= AUTO_APPROVE_THRESHOLD) {
                status = SignalStatus.AUTO_APPROVE;
            } else {
                status = SignalStatus.PENDING;
            }

            PolicyQuantSignalQueueEntry entry = PolicyQuantSignalQueueEntry.builder()
                    .sourceEventCardId(card.impactId())
                    .parameterKey("riskFreeRate")
                    .proposedValue(Double.isNaN(extractedValue) ? 0.0 : extractedValue)
                    .currentValue(macroState.getRiskFreeRate())
                    .confidence(confidence)
                    .status(status)
                    .build();
            repo.save(entry);

            if (status == SignalStatus.AUTO_APPROVE) {
                macroState.setRiskFreeRate(extractedValue, "AUTO");
                log.info("[PolicyQuant] Auto-approved riskFreeRate={} confidence={} source={}",
                        extractedValue, confidence, card.authority());
            } else {
                log.debug("[PolicyQuant] Queued PENDING signal authority={} channel={} confidence={}",
                        card.authority(), card.transmissionChannel(), confidence);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private boolean isTrustedAuthority(PolicyEventCard card) {
        return card.authority() == PolicyAuthority.RBI
                || card.authority() == PolicyAuthority.SEBI
                || card.authority() == PolicyAuthority.MINISTRY_OF_FINANCE
                || card.authority() == PolicyAuthority.CBDT;
    }

    /**
     * Phase 1: rate channel only. Extracts percentage values like "6.50%" or
     * "6.5 per cent" from the card's document title.
     */
    private double extractRateValue(PolicyEventCard card) {
        String text = card.documentTitle() != null ? card.documentTitle() : "";
        Matcher m = RATE_PATTERN.matcher(text);
        if (m.find()) {
            double pct = Double.parseDouble(m.group(1));
            if (pct > 1.0 && pct < 20.0) {
                return pct / 100.0;
            }
        }
        return Double.NaN;
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
