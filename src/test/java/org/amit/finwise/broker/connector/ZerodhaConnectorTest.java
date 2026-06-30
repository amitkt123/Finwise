package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ZerodhaConnectorTest {

    @Test
    void broker_returnsZerodha() {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "testkey");
        ReflectionTestUtils.setField(connector, "apiSecret", "testsecret");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://example.com/callback");
        assertThat(connector.broker()).isEqualTo(BrokerEnum.ZERODHA);
    }

    @Test
    void buildAuthUrl_containsApiKey() {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "mykey123");
        ReflectionTestUtils.setField(connector, "apiSecret", "secret");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://cb.example.com");
        String url = connector.buildAuthUrl();
        assertThat(url).contains("mykey123").contains("kite.zerodha.com");
    }

    @Test
    void computeChecksum_sha256OfConcatenation() throws Exception {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "api123");
        ReflectionTestUtils.setField(connector, "apiSecret", "secret456");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://cb");
        String checksum = connector.computeChecksum("reqtok789");
        // sha256("api123reqtok789secret456")
        assertThat(checksum).hasSize(64).matches("[0-9a-f]+");
    }
}
