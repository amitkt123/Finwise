package org.amit.finwise.broker.service;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HoldingDeduplicationService {

    public List<MergedHoldingDTO> merge(List<BrokerHoldingDTO> holdings) {
        Map<String, List<BrokerHoldingDTO>> byIsin = holdings.stream()
            .collect(Collectors.groupingBy(BrokerHoldingDTO::isin));

        return byIsin.values().stream()
            .map(this::mergeGroup)
            .toList();
    }

    private MergedHoldingDTO mergeGroup(List<BrokerHoldingDTO> group) {
        BigDecimal totalQty = group.stream()
            .map(BrokerHoldingDTO::quantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Weighted average cost: Σ(qty_i * avgCost_i) / Σ(qty_i)
        BigDecimal totalCostBasis = group.stream()
            .map(h -> h.quantity().multiply(h.avgCostPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal blendedAvgCost = totalQty.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : totalCostBasis.divide(totalQty, 2, RoundingMode.HALF_UP);

        BigDecimal totalCurrentValue = group.stream()
            .map(BrokerHoldingDTO::currentValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<BrokerEnum, BigDecimal> breakdown = new EnumMap<>(BrokerEnum.class);
        for (BrokerHoldingDTO h : group) {
            breakdown.merge(h.broker(), h.quantity(), BigDecimal::add);
        }

        BrokerHoldingDTO first = group.get(0);
        return new MergedHoldingDTO(
            first.isin(), first.symbol(), first.name(),
            totalQty, blendedAvgCost, totalCurrentValue, breakdown
        );
    }
}
