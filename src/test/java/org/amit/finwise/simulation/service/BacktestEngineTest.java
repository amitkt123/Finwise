package org.amit.finwise.simulation.service;

import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BacktestEngineTest {

    @Mock EodPriceRepository eodRepo;
    @InjectMocks BacktestEngine engine;

    private List<EodPrice> priceFor(String symbol, LocalDate start, double... closes) {
        var list = new java.util.ArrayList<EodPrice>();
        LocalDate d = start;
        for (double c : closes) {
            EodPrice p = new EodPrice();
            p.setSymbol(symbol);
            p.setTradeDate(d);
            p.setAdjClose(BigDecimal.valueOf(c));
            list.add(p);
            d = d.plusDays(1);
        }
        return list;
    }

    @Test
    void lumpsumValueSeriesTracksPrice() {
        var start = LocalDate.of(2023, 1, 2);
        when(eodRepo.findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate("INFY", start))
                .thenReturn(priceFor("INFY", start, 100.0, 110.0, 120.0));

        var req = new SimulationRequest("INFY", InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(10_000), start, 12);

        var result = engine.replay(req);

        assertEquals(3, result.history().size());
        // 10000 / 100 = 100 units; day2 value = 100 * 110 = 11000
        assertEquals(0, BigDecimal.valueOf(11_000).compareTo(result.history().get(1).value()));
        assertEquals(0, BigDecimal.valueOf(10_000).compareTo(result.totalInvested()));
        assertEquals(start, result.dataFrom());
    }

    @Test
    void sipAccumulatesUnitsMonthly() {
        var jan1 = LocalDate.of(2023, 1, 2);
        var feb1 = LocalDate.of(2023, 2, 1);
        var mar1 = LocalDate.of(2023, 3, 1);

        List<EodPrice> prices = List.of(
                eod("INFY", jan1, 100.0),
                eod("INFY", feb1, 110.0),
                eod("INFY", mar1, 120.0)
        );
        when(eodRepo.findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate("INFY", jan1))
                .thenReturn(prices);

        var req = new SimulationRequest("INFY", InstrumentType.STOCK, InvestmentMode.SIP,
                BigDecimal.valueOf(5_000), jan1, 12);

        var result = engine.replay(req);

        // Month 1: 5000/100 = 50 units; Month 2: 5000/110 ≈ 45.45 units; Month 3: 5000/120 ≈ 41.67 units
        // Total invested = 15000
        assertEquals(0, BigDecimal.valueOf(15_000).compareTo(result.totalInvested()));
        assertTrue(result.finalValue().compareTo(BigDecimal.valueOf(15_000)) > 0,
                "Final value should exceed invested amount given rising prices");
    }

    @Test
    void returnsEmptyHistoryForUnknownSymbol() {
        when(eodRepo.findBySymbolAndTradeDateGreaterThanEqualOrderByTradeDate(eq("ZZZZZ"),
                any(LocalDate.class))).thenReturn(List.of());

        var req = new SimulationRequest("ZZZZZ", InstrumentType.STOCK, InvestmentMode.LUMPSUM,
                BigDecimal.valueOf(1_000), LocalDate.now(), 12);

        var result = engine.replay(req);
        assertTrue(result.history().isEmpty());
    }

    private EodPrice eod(String symbol, LocalDate date, double close) {
        EodPrice p = new EodPrice();
        p.setSymbol(symbol);
        p.setTradeDate(date);
        p.setAdjClose(BigDecimal.valueOf(close));
        return p;
    }
}
