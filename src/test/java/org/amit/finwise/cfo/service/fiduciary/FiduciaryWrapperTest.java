package org.amit.finwise.cfo.service.fiduciary;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FiduciaryWrapperTest {

    private final ConflictDisclosureConfig config = new ConflictDisclosureConfig();
    private final FiduciaryWrapper wrapper = new FiduciaryWrapper(config);

    @Test
    void wrap_includesConflictStatement() {
        FiduciaryEnvelope<String> env = wrapper.wrap("hello", List.of("NSE"), null, null);
        assertThat(env.conflictStatement()).contains("NONE");
        assertThat(env.data()).isEqualTo("hello");
        assertThat(env.generatedAt()).isNotNull();
    }

    @Test
    void wrap_includesDataSources() {
        FiduciaryEnvelope<Integer> env = wrapper.wrap(42, List.of("NSE-bhavcopy", "FRED"), "EOD", "n=100");
        assertThat(env.dataSources()).containsExactly("NSE-bhavcopy", "FRED");
        assertThat(env.dataQualityNote()).isEqualTo("EOD");
        assertThat(env.confidenceSummary()).isEqualTo("n=100");
    }

    @Test
    void conflictStatement_dynamicFromConfig() {
        config.setConflictStatement("Conflict: Finwise earns 10bps on Zerodha transactions.");
        FiduciaryEnvelope<String> env = wrapper.wrap("x", List.of(), null, null);
        assertThat(env.conflictStatement()).contains("10bps");
    }
}
