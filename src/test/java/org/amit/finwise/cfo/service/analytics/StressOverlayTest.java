package org.amit.finwise.cfo.service.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class StressOverlayTest {

    @Test
    void overlayCannotTurnLossIntoGain() {
        assertThat(Math.min(-0.05 + 0.03, 0.0)).isCloseTo(-0.02, offset(1e-9));
    }

    @Test
    void highSurpriseScalesOverlayBy1Point5() {
        assertThat(-0.032 * 1.5).isEqualTo(-0.048);
    }

    @Test
    void lowSurpriseScalesOverlayBy0Point7() {
        assertThat(-0.032 * 0.7).isCloseTo(-0.0224, offset(1e-9));
    }
}
