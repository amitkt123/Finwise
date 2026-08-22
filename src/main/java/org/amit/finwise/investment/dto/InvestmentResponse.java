package org.amit.finwise.investment.dto;

import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.enums.RiskProfile;
import org.amit.finwise.investment.model.Investment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestmentResponse(
        Long id,
        InvestmentType type,
        String symbol,
        String name,
        String platform,
        String sector,
        LocalDate purchaseDate,
        BigDecimal quantity,
        BigDecimal costPerUnit,
        BigDecimal totalCost,
        BigDecimal currentPrice,
        BigDecimal currentValue,
        BigDecimal unrealizedGainLoss,
        BigDecimal gainLossPercentage,
        Boolean isActive,
        RiskProfile riskProfile,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal sumAssured,
        BigDecimal annualPremium
) {
    public static InvestmentResponse from(Investment i) {
        return new InvestmentResponse(
                i.getId(),
                i.getType(),
                i.getSymbol(),
                i.getName(),
                i.getPlatform(),
                i.getSector(),
                i.getPurchaseDate(),
                i.getQuantity(),
                i.getCostPerUnit(),
                i.getTotalCost(),
                i.getCurrentPrice(),
                i.getCurrentValue(),
                i.getUnrealizedGainLoss(),
                i.getGainLossPercentage(),
                i.getIsActive(),
                i.getRiskProfile(),
                i.getInterestRate(),
                i.getMaturityDate(),
                i.getSumAssured(),
                i.getAnnualPremium()
        );
    }
}
