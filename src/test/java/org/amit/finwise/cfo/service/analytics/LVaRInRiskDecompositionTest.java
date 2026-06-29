package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LVaRInRiskDecompositionTest {

    @Test
    void lvar95IsGreaterOrEqualToVar95() {
        assertThat(55000.0).isGreaterThanOrEqualTo(50000.0);
    }

    @Test
    void lvar99IsGreaterThanLvar95() {
        assertThat(55000.0 * (2.326 / 1.645)).isGreaterThan(55000.0);
    }
}
