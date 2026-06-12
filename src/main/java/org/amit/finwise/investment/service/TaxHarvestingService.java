package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Suggests tax-optimizing actions on the open FIFO lots — never executes them.
 *
 * Three suggestion classes:
 *  1. EXEMPTION HARVEST — realize long-term gains up to the unused ₹1.25L LTCG
 *     exemption this FY (sell and optionally rebuy; India has no wash-sale rule
 *     for listed equity, but the rebuy resets the holding period).
 *  2. BOUNDARY WAIT — lots in gain that turn long-term within 30 days: waiting
 *     converts the gain from STCG (20%) to LTCG (12.5%).
 *  3. LOSS HARVEST — lots in loss whose realization offsets gains under the
 *     statutory set-off rules (STCL against both heads, LTCL against LTCG only).
 *
 * Output is narrative input for the CFO brief; rupee amounts are computed here
 * so the LLM only explains, never calculates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxHarvestingService {

    private final LotTrackingService lotTrackingService;
    private final CapitalGainsTaxService capitalGainsTaxService;
    private final InvestmentRepository investmentRepository;

    private static final int BOUNDARY_WINDOW_DAYS = 30;

    public HarvestPlan suggest(String userId) {
        LocalDate today = LocalDate.now();
        CapitalGainsTaxService.RealizedTaxSummary realized =
                capitalGainsTaxService.realizedTax(userId, today);
        double headroom = realized.exemptionHeadroom().doubleValue();

        Map<String, Double> currentPrice = new HashMap<>();
        for (Investment inv : investmentRepository.findActiveInvestments(userId)) {
            if (inv.getSymbol() != null && inv.getCurrentPrice() != null) {
                currentPrice.put(inv.getSymbol().toUpperCase(), inv.getCurrentPrice().doubleValue());
            }
        }

        LotTrackingService.LotLedger ledger = lotTrackingService.buildLedger(userId);
        List<String> notes = new ArrayList<>(ledger.notes());
        if (ledger.openLotsBySymbol().isEmpty()) {
            notes.add("NO_LOT_HISTORY: no BUY/SELL transactions recorded — harvesting suggestions unavailable");
        }

        List<HarvestCandidate> exemptionHarvest = new ArrayList<>();
        List<BoundaryCandidate> boundaryWait = new ArrayList<>();
        List<LossCandidate> lossHarvest = new ArrayList<>();

        List<LotTrackingService.HoldingLot> allLots = ledger.openLotsBySymbol().values().stream()
                .flatMap(List::stream)
                .filter(lot -> currentPrice.containsKey(lot.symbol()))
                .toList();

        // 1. Exemption harvest: long-term lots in gain, smallest gain first so the
        //    headroom is filled with the least market disruption.
        double remainingHeadroom = headroom;
        for (LotTrackingService.HoldingLot lot : allLots.stream()
                .filter(l -> l.isLongTermAsOf(today))
                .sorted(Comparator.comparingDouble(l -> lotGain(l, currentPrice)))
                .toList()) {
            if (remainingHeadroom <= 0) break;
            double gainPerUnit = currentPrice.get(lot.symbol()) - lot.costPerUnit().doubleValue();
            if (gainPerUnit <= 0) continue;
            double lotGain = gainPerUnit * lot.quantity().doubleValue();
            double units = lotGain <= remainingHeadroom
                    ? lot.quantity().doubleValue()
                    : remainingHeadroom / gainPerUnit;
            double harvestedGain = Math.min(lotGain, remainingHeadroom);
            exemptionHarvest.add(new HarvestCandidate(
                    lot.symbol(), lot.buyDate(), units, harvestedGain,
                    harvestedGain * capitalGainsTaxService.ltcgRate()));
            remainingHeadroom -= harvestedGain;
        }

        // 2. Boundary wait: short-term lots in gain that turn long-term within 30 days
        for (LotTrackingService.HoldingLot lot : allLots) {
            long days = lot.daysToLongTerm(today);
            if (days <= 0 || days > BOUNDARY_WINDOW_DAYS) continue;
            double gain = lotGain(lot, currentPrice);
            if (gain <= 0) continue;
            double saving = gain * (capitalGainsTaxService.stcgRate() - capitalGainsTaxService.ltcgRate());
            boundaryWait.add(new BoundaryCandidate(lot.symbol(), lot.buyDate(), days, gain, saving));
        }
        boundaryWait.sort(Comparator.comparingDouble(BoundaryCandidate::taxSavingIfWaited).reversed());

        // 3. Loss harvest: lots in loss, largest loss first
        for (LotTrackingService.HoldingLot lot : allLots) {
            double gain = lotGain(lot, currentPrice);
            if (gain >= 0) continue;
            boolean longTerm = lot.isLongTermAsOf(today);
            lossHarvest.add(new LossCandidate(lot.symbol(), lot.buyDate(), -gain, longTerm,
                    longTerm ? "offsets LTCG only" : "offsets STCG first, then LTCG"));
        }
        lossHarvest.sort(Comparator.comparingDouble(LossCandidate::unrealizedLoss).reversed());

        return new HarvestPlan(
                realized.fyStart(), realized.fyEnd(),
                BigDecimal.valueOf(headroom),
                List.copyOf(exemptionHarvest),
                List.copyOf(boundaryWait),
                List.copyOf(lossHarvest),
                List.copyOf(notes));
    }

    private static double lotGain(LotTrackingService.HoldingLot lot, Map<String, Double> currentPrice) {
        return (currentPrice.get(lot.symbol()) - lot.costPerUnit().doubleValue())
                * lot.quantity().doubleValue();
    }

    public record HarvestCandidate(
            String symbol,
            LocalDate buyDate,
            double unitsToSell,
            double ltcgRealized,            // ₹ gain realized inside the exemption
            double taxSavedVsLater          // ₹ LTCG tax avoided by using this year's exemption
    ) {}

    public record BoundaryCandidate(
            String symbol,
            LocalDate buyDate,
            long daysToLongTerm,
            double unrealizedGain,
            double taxSavingIfWaited        // gain × (STCG − LTCG rate)
    ) {}

    public record LossCandidate(
            String symbol,
            LocalDate buyDate,
            double unrealizedLoss,          // positive ₹ figure
            boolean longTerm,
            String setOffScope
    ) {}

    public record HarvestPlan(
            LocalDate fyStart,
            LocalDate fyEnd,
            BigDecimal exemptionHeadroom,
            List<HarvestCandidate> exemptionHarvest,
            List<BoundaryCandidate> boundaryWait,
            List<LossCandidate> lossHarvest,
            List<String> notes
    ) {}
}
