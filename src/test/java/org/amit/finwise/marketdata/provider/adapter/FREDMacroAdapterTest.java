package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.amit.finwise.marketdata.provider.DataEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class FREDMacroAdapterTest {

    @Test
    void name_returnsFred() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        assertThat(adapter.name()).isEqualTo("fred");
    }

    @Test
    void supports_macroGlobalOnly() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        assertThat(adapter.supports(DataCapability.MACRO_GLOBAL)).isTrue();
        assertThat(adapter.supports(DataCapability.REAL_TIME_QUOTE)).isFalse();
    }

    @Test
    void isHealthy_falseWhenApiKeyBlank() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        ReflectionTestUtils.setField(adapter, "apiKey", "");
        assertThat(adapter.isHealthy()).isFalse();
    }

    @Test
    void isHealthy_trueWhenApiKeySet() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        ReflectionTestUtils.setField(adapter, "apiKey", "abc123");
        assertThat(adapter.isHealthy()).isTrue();
    }
}
