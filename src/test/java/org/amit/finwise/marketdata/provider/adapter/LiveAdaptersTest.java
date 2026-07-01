package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAdaptersTest {

    @Test void nseOptionChain_supports() {
        assertThat(new NSEOptionChainAdapter(null).supports(DataCapability.OPTION_CHAIN)).isTrue();
    }
    @Test void zerodhaQuote_supports() {
        ZerodhaQuoteAdapter a = new ZerodhaQuoteAdapter(null);
        assertThat(a.supports(DataCapability.REAL_TIME_QUOTE)).isTrue();
        assertThat(a.supports(DataCapability.HISTORICAL_OHLCV)).isTrue();
    }
    @Test void screener_supports() {
        assertThat(new ScreenerFundamentalsAdapter(null).supports(DataCapability.FUNDAMENTALS)).isTrue();
    }
}
