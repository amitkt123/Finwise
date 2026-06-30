package org.amit.finwise.broker.repository;

import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BrokerConnectionRepository extends JpaRepository<BrokerConnection, Long> {
    Optional<BrokerConnection> findByUserIdAndBroker(String userId, BrokerEnum broker);
    List<BrokerConnection> findAllByUserId(String userId);
    List<BrokerConnection> findByUserIdAndStatus(String userId, ConnectionStatus status);
}
