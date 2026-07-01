package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmallAdaptersTest {

    @Test void nseAnnouncements_name() {
        assertThat(new NSEAnnouncementsAdapter(null).name()).isEqualTo("nse-announcements");
    }
    @Test void nseAnnouncements_supports() {
        assertThat(new NSEAnnouncementsAdapter(null).supports(DataCapability.ANNOUNCEMENTS)).isTrue();
        assertThat(new NSEAnnouncementsAdapter(null).supports(DataCapability.MACRO_GLOBAL)).isFalse();
    }
    @Test void sebiInsider_name() {
        assertThat(new SEBIInsiderAdapter(null).name()).isEqualTo("sebi-insider");
    }
    @Test void worldBank_name() {
        assertThat(new WorldBankAdapter(null).name()).isEqualTo("world-bank");
    }
    @Test void worldBank_supports() {
        assertThat(new WorldBankAdapter(null).supports(DataCapability.WORLD_BANK)).isTrue();
    }
}
