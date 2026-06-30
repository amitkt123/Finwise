package org.amit.finwise.broker.service;

import org.amit.finwise.broker.connector.BrokerConnector;
import org.amit.finwise.broker.connector.BrokerConnectorRegistry;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.broker.repository.BrokerConnectionRepository;
import org.amit.finwise.common.TokenEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrokerSyncServiceTest {

    @Mock BrokerConnectionRepository connectionRepo;
    @Mock BrokerConnectorRegistry registry;
    @Mock HoldingDeduplicationService dedup;
    @Mock TokenEncryptionService tokenEncryptionService;
    @InjectMocks BrokerSyncService svc;

    @Test
    void syncAll_mergesHoldingsAcrossBrokers() {
        BrokerConnection conn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.DHAN)
            .encryptedAccessToken("enc").status(ConnectionStatus.ACTIVE).build();
        when(connectionRepo.findByUserIdAndStatus("u1", ConnectionStatus.ACTIVE)).thenReturn(List.of(conn));
        when(tokenEncryptionService.decrypt("enc")).thenReturn("plain-token");

        BrokerConnector mockConnector = mock(BrokerConnector.class);
        when(registry.get(BrokerEnum.DHAN)).thenReturn(mockConnector);
        BrokerHoldingDTO holding = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            BrokerEnum.DHAN, new BigDecimal("5"), new BigDecimal("2500"), new BigDecimal("13500"));
        when(mockConnector.syncHoldings("plain-token")).thenReturn(List.of(holding));

        MergedHoldingDTO merged = new MergedHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            new BigDecimal("5"), new BigDecimal("2500"), new BigDecimal("13500"), java.util.Map.of());
        when(dedup.merge(any())).thenReturn(List.of(merged));

        List<MergedHoldingDTO> result = svc.syncAll("u1");
        assertThat(result).hasSize(1);
        verify(connectionRepo).save(any()); // lastSyncedAt updated
    }
}
