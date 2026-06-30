package org.amit.finwise.marketdata.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataEnvelopeTest {

    @Test
    void of_isPresent() {
        DataEnvelope<Double> e = DataEnvelope.of(3.14, "test", DataQuality.LIVE);
        assertThat(e.isPresent()).isTrue();
        assertThat(e.valueOpt()).contains(3.14);
    }

    @Test
    void missing_isNotPresent() {
        DataEnvelope<Double> e = DataEnvelope.missing("test", "API down");
        assertThat(e.isPresent()).isFalse();
        assertThat(e.quality()).isEqualTo(DataQuality.MISSING);
        assertThat(e.fallbackNote()).isEqualTo("API down");
    }
}
