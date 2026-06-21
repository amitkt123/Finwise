package org.amit.finwise.investment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.amit.finwise.investment.enums.InvestmentType;

import java.math.BigDecimal;

public record AddInvestmentRequest(
        @NotNull InvestmentType type,
        @NotBlank String symbol,
        @NotBlank String name,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal costPerUnit,
        String platform
) {}
