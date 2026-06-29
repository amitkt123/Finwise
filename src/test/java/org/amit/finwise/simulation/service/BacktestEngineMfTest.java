package org.amit.finwise.simulation.service;

import org.amit.finwise.marketdata.model.MfNav;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.MfNavRepository;
import org.amit.finwise.simulation.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BacktestEngineMfTest {

    @Mock EodPriceRepository eodRepo;
    @Mock MfNavRepository mfNavRepo;
    @InjectMocks BacktestEngine engine;

    @Test
    void mfLumpsumValueSeriesTracksNav() {
        var start = LocalDate.of(2023, 1, 2);
        var end = LocalDate.now();
        var navs = List.of(
                nav("100120", start, 50.0),
                nav("100120", start.plusDays(1), 55.0),
                nav("100120", start.plusDays(2), 60.0)
        );
        when(mfNavRepo.findByAmfiCodeAndNavDateBetweenOrderByNavDate("100120", start, end))
                .thenReturn(navs);

        var req = new SimulationRequest("100120", InstrumentType.MF, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(10_000), start, 12);

        var result = engine.replay(req);

        assertEquals(3, result.history().size());
        // 10000 / 50 = 200 units; day2 = 200 * 55 = 11000
        assertEquals(0, BigDecimal.valueOf(11_000).compareTo(result.history().get(1).value()));
    }

    @Test
    void mfSipAccumulatesUnitsMonthly() {
        var jan = LocalDate.of(2023, 1, 2);
        var feb = LocalDate.of(2023, 2, 1);
        var end = LocalDate.now();
        var navs = List.of(nav("100120", jan, 50.0), nav("100120", feb, 60.0));
        when(mfNavRepo.findByAmfiCodeAndNavDateBetweenOrderByNavDate("100120", jan, end))
                .thenReturn(navs);

        var req = new SimulationRequest("100120", InstrumentType.MF, InvestmentMode.SIP,
                BigDecimal.valueOf(5_000), jan, 12);

        var result = engine.replay(req);

        // invested = 10000; final value should exceed invested given rising NAV
        assertEquals(0, BigDecimal.valueOf(10_000).compareTo(result.totalInvested()));
        assertTrue(result.finalValue().compareTo(BigDecimal.valueOf(10_000)) > 0);
    }

    private MfNav nav(String amfiCode, LocalDate date, double navVal) {
        MfNav n = new MfNav();
        n.setAmfiCode(amfiCode);
        n.setNavDate(date);
        n.setNav(BigDecimal.valueOf(navVal));
        return n;
    }
}
