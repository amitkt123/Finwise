package org.amit.finwise.broker.repository;

import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// No embedded DB (H2/HSQL/Derby) is on the classpath in this project — the only
// JDBC driver available is PostgreSQL, and the schema is normally Flyway-managed.
// The dev profile (active by default per application.properties) points at a real
// local Postgres with ddl-auto=update, so we reuse that datasource instead of
// asking @DataJpaTest to substitute an embedded one.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BrokerConnectionRepositoryTest {

    @Autowired BrokerConnectionRepository repo;

    @Test
    void findByUserIdAndBroker_returnsConnection() {
        BrokerConnection conn = BrokerConnection.builder()
            .userId("testuser")
            .broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken("enc-token")
            .status(ConnectionStatus.ACTIVE)
            .build();
        repo.save(conn);

        Optional<BrokerConnection> found = repo.findByUserIdAndBroker("testuser", BrokerEnum.ZERODHA);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ConnectionStatus.ACTIVE);
    }

    @Test
    void findAllByUserId_returnsAllBrokers() {
        repo.save(BrokerConnection.builder().userId("u1").broker(BrokerEnum.ZERODHA).status(ConnectionStatus.ACTIVE).build());
        repo.save(BrokerConnection.builder().userId("u1").broker(BrokerEnum.DHAN).status(ConnectionStatus.ACTIVE).build());

        assertThat(repo.findAllByUserId("u1")).hasSize(2);
    }
}
