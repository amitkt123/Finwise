package org.amit.finwise.cfo.service.insight;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsightCardServiceRegimeCaveatTest {

    @Test
    void goalCardAppendsCaveatWhenRegimeAdjusted() {
        var result = new org.amit.finwise.goal.model.GoalSimulationResult(
                "GBM", 10000, 120, 0.09, 0.24, false,
                10000.0, 5000000.0, 0.65,
                3000000, 3500000, 4000000, 4500000, 5000000,
                12000, 14000, 17000, "65% funded", java.util.List.of(),
                true,   // regimeAdjusted
                0.241   // effectiveSigma
        );
        assertThat(result.regimeAdjusted()).isTrue();
        assertThat(result.effectiveSigma()).isEqualTo(0.241);
    }
}
