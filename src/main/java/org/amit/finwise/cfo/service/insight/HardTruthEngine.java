package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Computation;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService.TwrrResult;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Brutal-truth" insight cards (honesty/compliance workstream): closet indexing, dormant
 * underperforming holdings, and mutual-fund fee drag. Each generator degrades to "no card"
 * rather than fabricating a number when its source data is unavailable, matching the rest of
 * this package's {@code InsightCardService} generators.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HardTruthEngine {

    private final PortfolioPerformanceService performanceService;
    private final InvestmentRepository investmentRepository;

    /** 3% annualized tracking error — at/above this, the portfolio isn't a closet indexer. */
    private static final double TE_NOT_CLOSET_INDEXING = 0.03;
    /** Below this tracking error, active fees are buying essentially zero differentiation. */
    private static final double TE_ALERT = 0.01;

    /** Fallback annual FD rate; not sourced from a live rate feed. */
    private static final double FD_RATE = 0.065;
    /** Minimum holding period, in months, before "dormant" applies. */
    private static final long DORMANT_MIN_MONTHS = 18;
    /** Annualized return at/below which underperformance is a large outright loss, not just lag. */
    private static final double DORMANT_ALERT_RETURN = -0.15;

    /** Representative index-fund TER. */
    private static final double PASSIVE_TER = 0.0012;
    /** Representative active-MF TER; not per-fund (per-fund TER isn't stored). */
    private static final double AVG_ACTIVE_TER = 0.0182;
    /** Minimum aggregate annual drag, in rupees, worth surfacing. */
    private static final double OVERFEE_MIN_DRAG = 10_000;
    /** Annual drag above which the overfee card is ALERT rather than WATCH. */
    private static final double OVERFEE_ALERT_DRAG = 100_000;

    /**
     * Build the hard-truth card set for a user. Each generator is called defensively so one
     * failing source never aborts the others.
     */
    public List<InsightCard> generateCards(String userId) {
        List<InsightCard> cards = new ArrayList<>();
        List<Investment> holdings = investmentRepository.findActiveInvestments(userId);

        try {
            benchmarkHuggerCard(userId).ifPresent(cards::add);
        } catch (RuntimeException e) {
            log.debug("[HardTruth] benchmarkHuggerCard failed: {}", e.getMessage());
        }

        try {
            cards.addAll(dormantHoldingCards(userId, holdings));
        } catch (RuntimeException e) {
            log.debug("[HardTruth] dormantHoldingCards failed: {}", e.getMessage());
        }

        try {
            overFeeCard(userId, holdings).ifPresent(cards::add);
        } catch (RuntimeException e) {
            log.debug("[HardTruth] overFeeCard failed: {}", e.getMessage());
        }

        return cards;
    }

    // ── Card A: closet indexing ────────────────────────────────────────────────

    Optional<InsightCard> benchmarkHuggerCard(String userId) {
        Optional<TwrrResult> twrrOpt = performanceService.computeTwrr(userId);
        if (twrrOpt.isEmpty()) return Optional.empty();
        TwrrResult t = twrrOpt.get();
        if (Double.isNaN(t.benchmarkTwrr()) || Double.isNaN(t.trackingErrorAnnualized())) {
            return Optional.empty();
        }
        double te = t.trackingErrorAnnualized();
        if (te >= TE_NOT_CLOSET_INDEXING) return Optional.empty();

        InsightCard.Severity sev = te < TE_ALERT ? InsightCard.Severity.ALERT : InsightCard.Severity.WATCH;

        List<Computation> comps = List.of(
                new Computation("Tracking error (annualized)", String.format("%.2f%%", te * 100),
                        "stdev of active sub-period returns × √annualization", "active return series",
                        t.from() + " → " + t.to()),
                Computation.of("Active return (annualized)", String.format("%+.2f%%", t.activeReturnAnnualized() * 100)),
                Computation.of("Information ratio", String.format("%.2f", t.informationRatio())));

        String title = String.format(
                "Closet indexing: tracking error only %.1f%% p.a. — you're paying active fees for near-index exposure.",
                te * 100);

        return Optional.of(InsightCard.builder("hard-truth-benchmark-hugger",
                        InsightCard.Category.SKILL, sev)
                .title(title)
                .computations(comps)
                .caveats(List.of("Low tracking error over a single window can also mean the portfolio genuinely "
                        + "is close to the benchmark by design — check mandate before switching to a passive fund."))
                .rawConfidence(0.55)
                .build());
    }

    // ── Card B: dormant/underperforming holdings ───────────────────────────────

    List<InsightCard> dormantHoldingCards(String userId, List<Investment> holdings) {
        List<InsightCard> out = new ArrayList<>();
        for (Investment inv : holdings) {
            try {
                if (inv.getPurchaseDate() == null || inv.getCurrentValue() == null
                        || inv.getCostPerUnit() == null || inv.getQuantity() == null) {
                    continue;
                }
                long months = ChronoUnit.MONTHS.between(inv.getPurchaseDate(), LocalDate.now());
                if (months < DORMANT_MIN_MONTHS) continue;

                double costBasis = inv.getCostPerUnit().multiply(inv.getQuantity()).doubleValue();
                if (costBasis <= 0) continue;

                double currentVal = inv.getCurrentValue().doubleValue();
                double annualizedReturn = Math.pow(currentVal / costBasis, 12.0 / months) - 1;
                if (Double.isNaN(annualizedReturn) || annualizedReturn >= FD_RATE) continue;

                double fdEquivalentVal = costBasis * Math.pow(1 + FD_RATE, months / 12.0);
                double opportunityCost = fdEquivalentVal - currentVal;

                InsightCard.Severity sev = annualizedReturn <= DORMANT_ALERT_RETURN
                        ? InsightCard.Severity.ALERT : InsightCard.Severity.WATCH;

                List<Computation> comps = List.of(
                        Computation.of("Symbol", inv.getSymbol()),
                        Computation.of("Months held", String.valueOf(months)),
                        Computation.of("Annualized return", String.format("%.1f%%", annualizedReturn * 100)),
                        Computation.of("FD rate (fallback)", String.format("%.1f%%", FD_RATE * 100)),
                        Computation.of("Opportunity cost vs FD", String.format("₹%,.0f", opportunityCost)));

                String title = String.format(
                        "%s returned %.1f%% p.a. over %d months — an FD would have paid %.1f%%. Opportunity cost: ₹%,.0f.",
                        inv.getSymbol(), annualizedReturn * 100, months, FD_RATE * 100, opportunityCost);

                out.add(InsightCard.builder("hard-truth-dormant-" + inv.getSymbol(),
                                InsightCard.Category.COST, sev)
                        .title(title)
                        .symbol(inv.getSymbol())
                        .computations(comps)
                        .caveats(List.of("FD rate is a static fallback, not a live rate — actual FD returns "
                                + "vary by tenure and issuer."))
                        .rawConfidence(0.5)
                        .build());
            } catch (RuntimeException e) {
                log.debug("[HardTruth] dormantHoldingCards skipped {}: {}", inv.getSymbol(), e.getMessage());
            }
        }
        return out;
    }

    // ── Card C: mutual-fund fee drag ───────────────────────────────────────────

    Optional<InsightCard> overFeeCard(String userId, List<Investment> holdings) {
        List<Investment> mfHoldings = holdings.stream()
                .filter(h -> h.getType() == InvestmentType.MUTUAL_FUND)
                .toList();
        if (mfHoldings.isEmpty()) return Optional.empty();

        double totalMfValue = mfHoldings.stream()
                .mapToDouble(h -> h.getCurrentValue() == null ? 0.0 : h.getCurrentValue().doubleValue())
                .sum();
        double annualDrag = totalMfValue * (AVG_ACTIVE_TER - PASSIVE_TER);
        if (annualDrag < OVERFEE_MIN_DRAG) return Optional.empty();

        InsightCard.Severity sev = annualDrag > OVERFEE_ALERT_DRAG
                ? InsightCard.Severity.ALERT : InsightCard.Severity.WATCH;

        List<Computation> comps = List.of(
                Computation.of("Total MF value", String.format("₹%,.0f", totalMfValue)),
                Computation.of("Avg active TER (assumed)", String.format("%.2f%%", AVG_ACTIVE_TER * 100)),
                Computation.of("Passive TER (assumed)", String.format("%.2f%%", PASSIVE_TER * 100)),
                Computation.of("Annual drag", String.format("₹%,.0f", annualDrag)));

        String title = String.format("Active MF fees ~%.2f%% vs passive ~%.2f%% — annual drag ₹%,.0f.",
                AVG_ACTIVE_TER * 100, PASSIVE_TER * 100, annualDrag);

        return Optional.of(InsightCard.builder("hard-truth-overfee",
                        InsightCard.Category.COST, sev)
                .title(title)
                .computations(comps)
                .caveats(List.of("TERs are representative assumptions, not each fund's actual expense ratio "
                        + "— per-fund TER is not currently stored."))
                .rawConfidence(0.5)
                .build());
    }
}
