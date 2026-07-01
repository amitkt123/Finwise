package org.amit.finwise.broker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.connector.BrokerConnectorRegistry;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.broker.repository.BrokerConnectionRepository;
import org.amit.finwise.common.TokenEncryptionService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrokerSyncService {

    private final BrokerConnectionRepository connectionRepo;
    private final BrokerConnectorRegistry registry;
    private final HoldingDeduplicationService dedup;
    private final TokenEncryptionService tokenEncryptionService;

    public List<MergedHoldingDTO> syncAll(String userId) {
        List<BrokerConnection> active = connectionRepo.findByUserIdAndStatus(userId, ConnectionStatus.ACTIVE);
        List<BrokerHoldingDTO> allHoldings = new ArrayList<>();

        for (BrokerConnection conn : active) {
            try {
                String token = tokenEncryptionService.decrypt(conn.getEncryptedAccessToken());
                List<BrokerHoldingDTO> holdings = registry.get(conn.getBroker()).syncHoldings(token, conn.getBrokerClientId());
                allHoldings.addAll(holdings);
                conn.setLastSyncedAt(Instant.now());
                connectionRepo.save(conn);
                log.info("[BrokerSync] {} — {} holdings synced for {}", conn.getBroker(), holdings.size(), userId);
            } catch (Exception e) {
                log.error("[BrokerSync] {} sync failed for {}: {}", conn.getBroker(), userId, e.getMessage());
            }
        }
        return dedup.merge(allHoldings);
    }
}
