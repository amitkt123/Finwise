package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DhanConnectorTest {
    @Test
    void broker_returnsDhan() {
        assertThat(new DhanConnector(null).broker()).isEqualTo(BrokerEnum.DHAN);
    }
}
