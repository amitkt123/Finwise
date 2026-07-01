package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BSEFilingsAdapterTest {
    @Test void name_returnsBse() {
        assertThat(new BSEFilingsAdapter(null).name()).isEqualTo("bse-filings");
    }
    @Test void supports_corporateFilings() {
        BSEFilingsAdapter a = new BSEFilingsAdapter(null);
        assertThat(a.supports(DataCapability.CORPORATE_FILINGS)).isTrue();
        assertThat(a.supports(DataCapability.REAL_TIME_QUOTE)).isFalse();
    }
}
