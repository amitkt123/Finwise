package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;

import java.time.LocalDate;
import java.util.List;

public interface BrokerConnector {
    BrokerEnum broker();
    List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken, String brokerClientId);
    List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since);
    BrokerConnection refreshToken(BrokerConnection connection);
}
