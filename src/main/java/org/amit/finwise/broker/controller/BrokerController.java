package org.amit.finwise.broker.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.auth.CurrentUserProvider;
import org.amit.finwise.broker.connector.ZerodhaConnector;
import org.amit.finwise.broker.connector.UpstoxConnector;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.broker.repository.BrokerConnectionRepository;
import org.amit.finwise.broker.service.BrokerSyncService;
import org.amit.finwise.common.TokenEncryptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broker")
@RequiredArgsConstructor
public class BrokerController {

    private final BrokerSyncService brokerSyncService;
    private final BrokerConnectionRepository connectionRepo;
    private final ZerodhaConnector zerodhaConnector;
    private final UpstoxConnector upstoxConnector;
    private final TokenEncryptionService tokenEncryptionService;

    @GetMapping("/status")
    public List<BrokerStatusDTO> status() {
        String userId = CurrentUserProvider.userId();
        return connectionRepo.findAllByUserId(userId).stream()
            .map(c -> new BrokerStatusDTO(c.getBroker(), c.getStatus(), c.getLastSyncedAt()))
            .toList();
    }

    @GetMapping("/zerodha/auth-url")
    public Map<String, String> zerodhaAuthUrl() {
        return Map.of("url", zerodhaConnector.buildAuthUrl());
    }

    @PostMapping("/zerodha/connect")
    public ResponseEntity<Map<String, String>> zerodhaConnect(@RequestParam String requestToken) {
        String userId = CurrentUserProvider.userId();
        BrokerConnection conn = zerodhaConnector.exchangeRequestToken(userId, requestToken);
        connectionRepo.findByUserIdAndBroker(userId, BrokerEnum.ZERODHA)
            .ifPresent(existing -> connectionRepo.delete(existing));
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "connected"));
    }

    @GetMapping("/upstox/auth-url")
    public Map<String, String> upstoxAuthUrl() {
        return Map.of("url", upstoxConnector.buildAuthUrl());
    }

    @PostMapping("/upstox/connect")
    public ResponseEntity<Map<String, String>> upstoxConnect(@RequestParam String code) {
        String userId = CurrentUserProvider.userId();
        BrokerConnection conn = upstoxConnector.exchangeCode(userId, code);
        connectionRepo.findByUserIdAndBroker(userId, BrokerEnum.UPSTOX)
            .ifPresent(existing -> connectionRepo.delete(existing));
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "connected"));
    }

    @PostMapping("/dhan/connect")
    public ResponseEntity<Map<String, String>> dhanConnect(@RequestParam String accessToken) {
        String userId = CurrentUserProvider.userId();
        BrokerConnection conn = BrokerConnection.builder()
            .userId(userId).broker(BrokerEnum.DHAN)
            .encryptedAccessToken(tokenEncryptionService.encrypt(accessToken))
            .status(ConnectionStatus.ACTIVE).build();
        connectionRepo.findByUserIdAndBroker(userId, BrokerEnum.DHAN)
            .ifPresent(connectionRepo::delete);
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "connected"));
    }

    @PostMapping("/angel/connect")
    public ResponseEntity<Map<String, String>> angelConnect(@RequestParam String jwtToken, @RequestParam String clientCode) {
        String userId = CurrentUserProvider.userId();
        BrokerConnection conn = BrokerConnection.builder()
            .userId(userId).broker(BrokerEnum.ANGEL)
            .encryptedAccessToken(tokenEncryptionService.encrypt(jwtToken))
            .brokerClientId(clientCode)
            .status(ConnectionStatus.ACTIVE).build();
        connectionRepo.findByUserIdAndBroker(userId, BrokerEnum.ANGEL)
            .ifPresent(connectionRepo::delete);
        connectionRepo.save(conn);
        return ResponseEntity.ok(Map.of("status", "connected"));
    }

    @PostMapping("/sync")
    public List<MergedHoldingDTO> sync() {
        return brokerSyncService.syncAll(CurrentUserProvider.userId());
    }

    @DeleteMapping("/{broker}")
    public ResponseEntity<Void> revoke(@PathVariable BrokerEnum broker) {
        String userId = CurrentUserProvider.userId();
        connectionRepo.findByUserIdAndBroker(userId, broker).ifPresent(c -> {
            c.setStatus(ConnectionStatus.REVOKED);
            connectionRepo.save(c);
        });
        return ResponseEntity.noContent().build();
    }

    record BrokerStatusDTO(BrokerEnum broker, ConnectionStatus status, java.time.Instant lastSyncedAt) {}
}
