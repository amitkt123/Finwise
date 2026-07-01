package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerConnectorRegistryTest {

    @Test
    void get_returnsRegisteredConnector() {
        BrokerConnector stub = new BrokerConnector() {
            public BrokerEnum broker() { return BrokerEnum.DHAN; }
            public List<BrokerHoldingDTO> syncHoldings(String t, String c) { return List.of(); }
            public List<BrokerTransactionDTO> syncTransactions(String t, LocalDate d) { return List.of(); }
            public BrokerConnection refreshToken(BrokerConnection c) { return c; }
        };
        BrokerConnectorRegistry registry = new BrokerConnectorRegistry(List.of(stub));
        assertThat(registry.get(BrokerEnum.DHAN)).isSameAs(stub);
    }

    @Test
    void get_throwsForUnregistered() {
        BrokerConnectorRegistry registry = new BrokerConnectorRegistry(List.of());
        assertThatThrownBy(() -> registry.get(BrokerEnum.ZERODHA))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
