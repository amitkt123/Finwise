package org.amit.finwise.investment.service;

import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.repository.PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestmentServiceTest {

    @Mock InvestmentRepository investmentRepository;
    @Mock PortfolioRepository portfolioRepository;
    @Mock PortfolioRiskService portfolioRiskService;
    @Mock CapitalGainsTaxService capitalGainsTaxService;
    @Mock BondAnalyticsService bondAnalyticsService;

    private InvestmentService service;

    @BeforeEach
    void setUp() {
        service = new InvestmentService(investmentRepository, portfolioRepository,
                portfolioRiskService, capitalGainsTaxService, bondAnalyticsService);
        when(investmentRepository.save(org.mockito.ArgumentMatchers.any(Investment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addInvestment_persistsFixedDepositFieldsForTaxComputation() {
        service.addInvestment("u", InvestmentType.FIXED_DEPOSIT, "HDFC-FD-1", "HDFC Bank FD",
                LocalDate.parse("2024-01-01"), BigDecimal.valueOf(1), BigDecimal.valueOf(100_000),
                "HDFC Bank", BigDecimal.valueOf(7.1), LocalDate.parse("2027-01-01"), null, null);

        ArgumentCaptor<Investment> captor = ArgumentCaptor.forClass(Investment.class);
        verify(investmentRepository).save(captor.capture());
        Investment saved = captor.getValue();

        assertEquals(0, saved.getInterestRate().compareTo(BigDecimal.valueOf(7.1)));
        assertEquals(LocalDate.parse("2027-01-01"), saved.getMaturityDate());
    }

    @Test
    void addInvestment_persistsInsurancePolicyFieldsForTaxComputation() {
        service.addInvestment("u", InvestmentType.INSURANCE_POLICY, "LIC-1", "LIC Jeevan Anand",
                LocalDate.parse("2020-01-01"), BigDecimal.ONE, BigDecimal.valueOf(50_000),
                "LIC", null, null, BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000));

        ArgumentCaptor<Investment> captor = ArgumentCaptor.forClass(Investment.class);
        verify(investmentRepository).save(captor.capture());
        Investment saved = captor.getValue();

        assertEquals(0, saved.getSumAssured().compareTo(BigDecimal.valueOf(1_000_000)));
        assertEquals(0, saved.getAnnualPremium().compareTo(BigDecimal.valueOf(50_000)));
    }
}
