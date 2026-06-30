package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class BrokerConnectorRegistry {

    private final Map<BrokerEnum, BrokerConnector> connectors;

    public BrokerConnectorRegistry(List<BrokerConnector> connectorList) {
        this.connectors = connectorList.stream()
            .collect(Collectors.toMap(BrokerConnector::broker, Function.identity()));
    }

    public BrokerConnector get(BrokerEnum broker) {
        BrokerConnector connector = connectors.get(broker);
        if (connector == null) throw new IllegalArgumentException("No connector registered for " + broker);
        return connector;
    }

    public boolean supports(BrokerEnum broker) {
        return connectors.containsKey(broker);
    }
}
