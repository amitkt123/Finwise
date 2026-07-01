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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        when(mockConnector.syncHoldings("plain-token", null)).thenReturn(List.of(holding));

        MergedHoldingDTO merged = new MergedHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            new BigDecimal("5"), new BigDecimal("2500"), new BigDecimal("13500"), java.util.Map.of());
        when(dedup.merge(any())).thenReturn(List.of(merged));

        List<MergedHoldingDTO> result = svc.syncAll("u1");
        assertThat(result).hasSize(1);
        verify(connectionRepo).save(any()); // lastSyncedAt updated
    }

    @Test
    void syncAll_genuinelyMergesHoldingsFromTwoDistinctBrokers() {
        BrokerConnection zerodhaConn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken("enc-z").status(ConnectionStatus.ACTIVE).build();
        BrokerConnection dhanConn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.DHAN)
            .encryptedAccessToken("enc-d").status(ConnectionStatus.ACTIVE).build();
        when(connectionRepo.findByUserIdAndStatus("u1", ConnectionStatus.ACTIVE))
            .thenReturn(List.of(zerodhaConn, dhanConn));
        when(tokenEncryptionService.decrypt("enc-z")).thenReturn("plain-token-z");
        when(tokenEncryptionService.decrypt("enc-d")).thenReturn("plain-token-d");

        BrokerConnector zerodhaConnector = mock(BrokerConnector.class);
        BrokerConnector dhanConnector = mock(BrokerConnector.class);
        when(registry.get(BrokerEnum.ZERODHA)).thenReturn(zerodhaConnector);
        when(registry.get(BrokerEnum.DHAN)).thenReturn(dhanConnector);

        BrokerHoldingDTO zerodhaHolding = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            BrokerEnum.ZERODHA, new BigDecimal("5"), new BigDecimal("2500"), new BigDecimal("13500"));
        BrokerHoldingDTO dhanHolding = new BrokerHoldingDTO("INE467B01029", "TCS", "Tata Consultancy Services",
            BrokerEnum.DHAN, new BigDecimal("2"), new BigDecimal("3400"), new BigDecimal("7200"));
        when(zerodhaConnector.syncHoldings("plain-token-z", null)).thenReturn(List.of(zerodhaHolding));
        when(dhanConnector.syncHoldings("plain-token-d", null)).thenReturn(List.of(dhanHolding));

        when(dedup.merge(any())).thenReturn(List.of());

        svc.syncAll("u1");

        ArgumentCaptor<List<BrokerHoldingDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(dedup).merge(captor.capture());
        assertThat(captor.getValue())
            .hasSize(2)
            .extracting(BrokerHoldingDTO::isin)
            .containsExactlyInAnyOrder("INE002A01018", "INE467B01029");
        verify(connectionRepo, times(2)).save(any());
    }

    @Test
    void syncAll_continuesWhenOneBrokerConnectorThrows() {
        BrokerConnection zerodhaConn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken("enc-z").status(ConnectionStatus.ACTIVE).build();
        BrokerConnection dhanConn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.DHAN)
            .encryptedAccessToken("enc-d").status(ConnectionStatus.ACTIVE).build();
        when(connectionRepo.findByUserIdAndStatus("u1", ConnectionStatus.ACTIVE))
            .thenReturn(List.of(zerodhaConn, dhanConn));
        when(tokenEncryptionService.decrypt("enc-z")).thenReturn("plain-token-z");
        when(tokenEncryptionService.decrypt("enc-d")).thenReturn("plain-token-d");

        BrokerConnector zerodhaConnector = mock(BrokerConnector.class);
        BrokerConnector dhanConnector = mock(BrokerConnector.class);
        when(registry.get(BrokerEnum.ZERODHA)).thenReturn(zerodhaConnector);
        when(registry.get(BrokerEnum.DHAN)).thenReturn(dhanConnector);

        when(zerodhaConnector.syncHoldings("plain-token-z", null)).thenThrow(new RuntimeException("Zerodha API down"));
        BrokerHoldingDTO dhanHolding = new BrokerHoldingDTO("INE467B01029", "TCS", "Tata Consultancy Services",
            BrokerEnum.DHAN, new BigDecimal("2"), new BigDecimal("3400"), new BigDecimal("7200"));
        when(dhanConnector.syncHoldings("plain-token-d", null)).thenReturn(List.of(dhanHolding));

        when(dedup.merge(any())).thenReturn(List.of());

        assertThatCode(() -> svc.syncAll("u1")).doesNotThrowAnyException();

        ArgumentCaptor<List<BrokerHoldingDTO>> captor = ArgumentCaptor.forClass(List.class);
        verify(dedup).merge(captor.capture());
        assertThat(captor.getValue())
            .hasSize(1)
            .extracting(BrokerHoldingDTO::isin)
            .containsExactly("INE467B01029");
        verify(connectionRepo).save(dhanConn); // only the successful connection's lastSyncedAt is updated
    }
}
