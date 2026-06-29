package org.amit.finwise.simulation.service;

import org.amit.finwise.simulation.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BacktestEngineTest {

    @Test
    void dtoRecordsCompile() {
        var point = new ChartPoint(LocalDate.now(), BigDecimal.TEN);
        assertEquals(BigDecimal.TEN, point.value());

        var band = new ScenarioBand(LocalDate.now(),
                BigDecimal.valueOf(1200), BigDecimal.valueOf(1000), BigDecimal.valueOf(800));
        assertEquals(BigDecimal.valueOf(1000), band.neutral());

        var mc = new MonteCarloInterval(LocalDate.now(),
                BigDecimal.valueOf(700), BigDecimal.valueOf(1000), BigDecimal.valueOf(1400));
        assertEquals(BigDecimal.valueOf(700), mc.p5());
    }
}
