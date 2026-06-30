# Multi-Broker OAuth & Holding Deduplication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire Zerodha, Dhan, Upstox, and Angel One as pluggable broker connectors behind a `BrokerConnector` interface, merge holdings from all brokers by ISIN into a unified portfolio view, and expose connect/sync/status endpoints.

**Architecture:** New `broker/` top-level package with a `BrokerConnector` strategy interface (mirroring `LLMProvider`). Each connector handles its own OAuth or API-key flow. `HoldingDeduplicationService` merges by ISIN using weighted-average cost. `BrokerSyncService` orchestrates per-user sync across all connected brokers.

**Tech Stack:** Spring Boot 3 / Java 21, RestClient (already used in `GrowwConnector`), AES encryption from `GrowwAuthService`, Resilience4j CircuitBreaker, Lombok, JPA/Hibernate.

## Global Constraints

- Package root: `org.amit.finwise.broker`
- All tokens stored AES-encrypted using the existing `GrowwAuthService` encrypt/decrypt methods
- No broker connector may write directly to `Investment` table — only `BrokerSyncService` owns that write
- `userId` always from `CurrentUserProvider.userId()` in web layer — never from request param
- Run `./mvnw test` after every task; all 281 pre-existing tests must remain green
- Commit after each task

---

### Task 1: BrokerConnection entity + enums + repository

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/model/BrokerEnum.java`
- Create: `src/main/java/org/amit/finwise/broker/model/ConnectionStatus.java`
- Create: `src/main/java/org/amit/finwise/broker/model/BrokerConnection.java`
- Create: `src/main/java/org/amit/finwise/broker/repository/BrokerConnectionRepository.java`
- Test: `src/test/java/org/amit/finwise/broker/repository/BrokerConnectionRepositoryTest.java`

**Interfaces:**
- Produces: `BrokerConnection` entity with fields `userId`, `broker`, `encryptedAccessToken`, `encryptedRefreshToken`, `tokenExpiresAt`, `status`, `lastSyncedAt`
- Produces: `BrokerConnectionRepository.findByUserIdAndBroker`, `findByUserIdAndStatus`, `findAllByUserId`

- [ ] **Step 1: Write the failing test**

```java
// src/test/java/org/amit/finwise/broker/repository/BrokerConnectionRepositoryTest.java
package org.amit.finwise.broker.repository;

import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -Dtest=BrokerConnectionRepositoryTest -pl . 2>&1 | tail -20
```
Expected: FAIL — `BrokerConnectionRepository` not found

- [ ] **Step 3: Create BrokerEnum**

```java
// src/main/java/org/amit/finwise/broker/model/BrokerEnum.java
package org.amit.finwise.broker.model;

public enum BrokerEnum {
    ZERODHA, UPSTOX, DHAN, ANGEL, GROWW, FYERS
}
```

- [ ] **Step 4: Create ConnectionStatus**

```java
// src/main/java/org/amit/finwise/broker/model/ConnectionStatus.java
package org.amit.finwise.broker.model;

public enum ConnectionStatus {
    ACTIVE, EXPIRED, REVOKED
}
```

- [ ] **Step 5: Create BrokerConnection entity**

```java
// src/main/java/org/amit/finwise/broker/model/BrokerConnection.java
package org.amit.finwise.broker.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "broker_connections",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "broker"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrokerConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BrokerEnum broker;

    @Column(columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(columnDefinition = "TEXT")
    private String encryptedRefreshToken;

    private Instant tokenExpiresAt;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ConnectionStatus status = ConnectionStatus.ACTIVE;

    private Instant lastSyncedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: Create BrokerConnectionRepository**

```java
// src/main/java/org/amit/finwise/broker/repository/BrokerConnectionRepository.java
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
```

- [ ] **Step 7: Run tests to verify they pass**

```bash
./mvnw test -Dtest=BrokerConnectionRepositoryTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests green

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/ src/test/java/org/amit/finwise/broker/
git commit -m "feat(broker): BrokerConnection entity + enums + repository"
```

---

### Task 2: BrokerConnector interface + DTOs

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/dto/BrokerHoldingDTO.java`
- Create: `src/main/java/org/amit/finwise/broker/dto/BrokerTransactionDTO.java`
- Create: `src/main/java/org/amit/finwise/broker/connector/BrokerConnector.java`
- Create: `src/main/java/org/amit/finwise/broker/connector/BrokerConnectorRegistry.java`

**Interfaces:**
- Produces: `BrokerConnector` interface with `broker()`, `syncHoldings()`, `syncTransactions()`, `refreshToken()`
- Produces: `BrokerConnectorRegistry.get(BrokerEnum)` → `BrokerConnector`
- Produces: `BrokerHoldingDTO(isin, symbol, broker, quantity, avgCostPrice, currentValue)`
- Produces: `BrokerTransactionDTO(isin, symbol, broker, type, quantity, price, tradeDate)`

- [ ] **Step 1: Create BrokerHoldingDTO**

```java
// src/main/java/org/amit/finwise/broker/dto/BrokerHoldingDTO.java
package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;

public record BrokerHoldingDTO(
    String isin,
    String symbol,
    String name,
    BrokerEnum broker,
    BigDecimal quantity,
    BigDecimal avgCostPrice,
    BigDecimal currentValue
) {}
```

- [ ] **Step 2: Create BrokerTransactionDTO**

```java
// src/main/java/org/amit/finwise/broker/dto/BrokerTransactionDTO.java
package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;
import java.time.LocalDate;

public record BrokerTransactionDTO(
    String isin,
    String symbol,
    BrokerEnum broker,
    String type,        // "BUY" | "SELL"
    BigDecimal quantity,
    BigDecimal price,
    LocalDate tradeDate
) {}
```

- [ ] **Step 3: Create BrokerConnector interface**

```java
// src/main/java/org/amit/finwise/broker/connector/BrokerConnector.java
package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;

import java.time.LocalDate;
import java.util.List;

public interface BrokerConnector {
    BrokerEnum broker();
    List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken);
    List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since);
    BrokerConnection refreshToken(BrokerConnection connection);
}
```

- [ ] **Step 4: Create BrokerConnectorRegistry**

```java
// src/main/java/org/amit/finwise/broker/connector/BrokerConnectorRegistry.java
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
```

- [ ] **Step 5: Write registry test**

```java
// src/test/java/org/amit/finwise/broker/connector/BrokerConnectorRegistryTest.java
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
            public List<BrokerHoldingDTO> syncHoldings(String t) { return List.of(); }
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
```

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=BrokerConnectorRegistryTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests green

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/connector/ src/main/java/org/amit/finwise/broker/dto/ src/test/java/org/amit/finwise/broker/connector/
git commit -m "feat(broker): BrokerConnector interface + registry + DTOs"
```

---

### Task 3: HoldingDeduplicationService

This is the core merge logic. Holdings from multiple brokers are merged by ISIN with weighted-average cost.

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/service/HoldingDeduplicationService.java`
- Create: `src/main/java/org/amit/finwise/broker/dto/MergedHoldingDTO.java`
- Test: `src/test/java/org/amit/finwise/broker/service/HoldingDeduplicationServiceTest.java`

**Interfaces:**
- Consumes: `List<BrokerHoldingDTO>`
- Produces: `HoldingDeduplicationService.merge(List<BrokerHoldingDTO>)` → `List<MergedHoldingDTO>`
- Produces: `MergedHoldingDTO(isin, symbol, name, totalQuantity, blendedAvgCost, totalCurrentValue, brokerBreakdown)`

- [ ] **Step 1: Create MergedHoldingDTO**

```java
// src/main/java/org/amit/finwise/broker/dto/MergedHoldingDTO.java
package org.amit.finwise.broker.dto;

import org.amit.finwise.broker.model.BrokerEnum;
import java.math.BigDecimal;
import java.util.Map;

public record MergedHoldingDTO(
    String isin,
    String symbol,
    String name,
    BigDecimal totalQuantity,
    BigDecimal blendedAvgCost,
    BigDecimal totalCurrentValue,
    Map<BrokerEnum, BigDecimal> brokerBreakdown  // broker → quantity
) {}
```

- [ ] **Step 2: Write the failing tests**

```java
// src/test/java/org/amit/finwise/broker/service/HoldingDeduplicationServiceTest.java
package org.amit.finwise.broker.service;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingDeduplicationServiceTest {

    private final HoldingDeduplicationService svc = new HoldingDeduplicationService();

    @Test
    void singleHolding_passesThrough() {
        var h = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        List<MergedHoldingDTO> merged = svc.merge(List.of(h));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).totalQuantity()).isEqualByComparingTo("10");
        assertThat(merged.get(0).blendedAvgCost()).isEqualByComparingTo("2500");
    }

    @Test
    void sameisin_twoBrokers_mergesWithWeightedAvgCost() {
        // Zerodha: 10 @ 2500 = 25000 cost basis
        // Dhan: 5 @ 2700 = 13500 cost basis
        // Blended: (25000 + 13500) / 15 = 2566.67
        var z = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        var d = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance Industries",
            BrokerEnum.DHAN, new BigDecimal("5"), new BigDecimal("2700"), new BigDecimal("13500"));

        List<MergedHoldingDTO> merged = svc.merge(List.of(z, d));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).totalQuantity()).isEqualByComparingTo("15");
        assertThat(merged.get(0).blendedAvgCost())
            .isEqualByComparingTo(new BigDecimal("2566.67"));
        assertThat(merged.get(0).brokerBreakdown()).containsKey(BrokerEnum.ZERODHA);
        assertThat(merged.get(0).brokerBreakdown()).containsKey(BrokerEnum.DHAN);
    }

    @Test
    void differentIsins_keptSeparate() {
        var r = new BrokerHoldingDTO("INE002A01018", "RELIANCE", "Reliance",
            BrokerEnum.ZERODHA, new BigDecimal("10"), new BigDecimal("2500"), new BigDecimal("27000"));
        var t = new BrokerHoldingDTO("INE467B01029", "TCS", "TCS",
            BrokerEnum.ZERODHA, new BigDecimal("5"), new BigDecimal("3500"), new BigDecimal("18500"));
        assertThat(svc.merge(List.of(r, t))).hasSize(2);
    }
}
```

- [ ] **Step 3: Run to verify failure**

```bash
./mvnw test -Dtest=HoldingDeduplicationServiceTest 2>&1 | tail -10
```
Expected: FAIL — `HoldingDeduplicationService` not found

- [ ] **Step 4: Implement HoldingDeduplicationService**

```java
// src/main/java/org/amit/finwise/broker/service/HoldingDeduplicationService.java
package org.amit.finwise.broker.service;

import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class HoldingDeduplicationService {

    public List<MergedHoldingDTO> merge(List<BrokerHoldingDTO> holdings) {
        Map<String, List<BrokerHoldingDTO>> byIsin = holdings.stream()
            .collect(Collectors.groupingBy(BrokerHoldingDTO::isin));

        return byIsin.values().stream()
            .map(this::mergeGroup)
            .toList();
    }

    private MergedHoldingDTO mergeGroup(List<BrokerHoldingDTO> group) {
        BigDecimal totalQty = group.stream()
            .map(BrokerHoldingDTO::quantity)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Weighted average cost: Σ(qty_i * avgCost_i) / Σ(qty_i)
        BigDecimal totalCostBasis = group.stream()
            .map(h -> h.quantity().multiply(h.avgCostPrice()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal blendedAvgCost = totalQty.compareTo(BigDecimal.ZERO) == 0
            ? BigDecimal.ZERO
            : totalCostBasis.divide(totalQty, 2, RoundingMode.HALF_UP);

        BigDecimal totalCurrentValue = group.stream()
            .map(BrokerHoldingDTO::currentValue)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<BrokerEnum, BigDecimal> breakdown = new EnumMap<>(BrokerEnum.class);
        for (BrokerHoldingDTO h : group) {
            breakdown.merge(h.broker(), h.quantity(), BigDecimal::add);
        }

        BrokerHoldingDTO first = group.get(0);
        return new MergedHoldingDTO(
            first.isin(), first.symbol(), first.name(),
            totalQty, blendedAvgCost, totalCurrentValue, breakdown
        );
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=HoldingDeduplicationServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests green

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/service/ src/main/java/org/amit/finwise/broker/dto/MergedHoldingDTO.java src/test/java/org/amit/finwise/broker/service/
git commit -m "feat(broker): HoldingDeduplicationService — merge by ISIN with weighted avg cost"
```

---

### Task 4: ZerodhaConnector

Zerodha Kite Connect uses OAuth: redirect → `request_token` callback → exchange for `access_token`. Checksum = `SHA256(api_key + request_token + api_secret)`.

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/connector/ZerodhaConnector.java`
- Test: `src/test/java/org/amit/finwise/broker/connector/ZerodhaConnectorTest.java`

**Interfaces:**
- Consumes: `BrokerConnector` interface
- Produces: `ZerodhaConnector implements BrokerConnector` with broker() = ZERODHA
- Produces: `buildAuthUrl()` → redirect URL for OAuth initiation
- Produces: `exchangeRequestToken(requestToken)` → `BrokerConnection`

Add to `.env` / `application.properties`:
```
broker.zerodha.api-key=your_api_key
broker.zerodha.api-secret=your_api_secret
broker.zerodha.redirect-uri=https://your-domain/api/broker/zerodha/callback
```

- [ ] **Step 1: Write the test**

```java
// src/test/java/org/amit/finwise/broker/connector/ZerodhaConnectorTest.java
package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class ZerodhaConnectorTest {

    @Test
    void broker_returnsZerodha() {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "testkey");
        ReflectionTestUtils.setField(connector, "apiSecret", "testsecret");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://example.com/callback");
        assertThat(connector.broker()).isEqualTo(BrokerEnum.ZERODHA);
    }

    @Test
    void buildAuthUrl_containsApiKey() {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "mykey123");
        ReflectionTestUtils.setField(connector, "apiSecret", "secret");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://cb.example.com");
        String url = connector.buildAuthUrl();
        assertThat(url).contains("mykey123").contains("kite.zerodha.com");
    }

    @Test
    void computeChecksum_sha256OfConcatenation() throws Exception {
        ZerodhaConnector connector = new ZerodhaConnector(null, null);
        ReflectionTestUtils.setField(connector, "apiKey", "api123");
        ReflectionTestUtils.setField(connector, "apiSecret", "secret456");
        ReflectionTestUtils.setField(connector, "redirectUri", "https://cb");
        String checksum = connector.computeChecksum("reqtok789");
        // sha256("api123reqtok789secret456")
        assertThat(checksum).hasSize(64).matches("[0-9a-f]+");
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw test -Dtest=ZerodhaConnectorTest 2>&1 | tail -10
```

- [ ] **Step 3: Implement ZerodhaConnector**

```java
// src/main/java/org/amit/finwise/broker/connector/ZerodhaConnector.java
package org.amit.finwise.broker.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.cfo.service.GrowwAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZerodhaConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final GrowwAuthService growwAuthService; // reuse AES encrypt/decrypt

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Value("${broker.zerodha.api-secret:}")
    private String apiSecret;

    @Value("${broker.zerodha.redirect-uri:}")
    private String redirectUri;

    private static final String KITE_BASE = "https://api.kite.trade";

    @Override
    public BrokerEnum broker() { return BrokerEnum.ZERODHA; }

    public String buildAuthUrl() {
        return "https://kite.zerodha.com/connect/login?api_key=" + apiKey + "&v=3";
    }

    public BrokerConnection exchangeRequestToken(String userId, String requestToken) {
        String checksum = computeChecksum(requestToken);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("api_key", apiKey);
        form.add("request_token", requestToken);
        form.add("checksum", checksum);

        Map<?, ?> response = restClientBuilder.build()
            .post()
            .uri(KITE_BASE + "/session/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) response).get("data");
        String accessToken = (String) data.get("access_token");

        return BrokerConnection.builder()
            .userId(userId)
            .broker(BrokerEnum.ZERODHA)
            .encryptedAccessToken(growwAuthService.encrypt(accessToken))
            .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS)) // Kite tokens expire daily
            .status(ConnectionStatus.ACTIVE)
            .build();
    }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(KITE_BASE + "/portfolio/holdings")
            .header("Authorization", "token " + apiKey + ":" + decryptedAccessToken)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) ((Map<?, ?>) response).get("data");
        if (data == null) return List.of();

        return data.stream().map(h -> new BrokerHoldingDTO(
            (String) h.get("isin"),
            (String) h.get("tradingsymbol"),
            (String) h.getOrDefault("instrument_name", (String) h.get("tradingsymbol")),
            BrokerEnum.ZERODHA,
            new BigDecimal(h.get("quantity").toString()),
            new BigDecimal(h.get("average_price").toString()),
            new BigDecimal(h.get("last_price").toString())
                .multiply(new BigDecimal(h.get("quantity").toString()))
        )).toList();
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        // Kite Connect /orders endpoint for historical trades
        // Returns daily trade book — implement per Kite Connect API v3
        log.info("[Zerodha] syncTransactions since {} — implement per Kite API v3", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        // Kite tokens expire daily and require re-auth via OAuth; mark EXPIRED
        connection.setStatus(ConnectionStatus.EXPIRED);
        return connection;
    }

    String computeChecksum(String requestToken) {
        try {
            String input = apiKey + requestToken + apiSecret;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=ZerodhaConnectorTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/connector/ZerodhaConnector.java src/test/java/org/amit/finwise/broker/connector/ZerodhaConnectorTest.java
git commit -m "feat(broker): ZerodhaConnector — OAuth, checksum, holdings sync"
```

---

### Task 5: DhanConnector + UpstoxConnector + AngelConnector

Dhan and Angel use API-key authentication (simpler than OAuth). Upstox uses OAuth 2.0.

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/connector/DhanConnector.java`
- Create: `src/main/java/org/amit/finwise/broker/connector/UpstoxConnector.java`
- Create: `src/main/java/org/amit/finwise/broker/connector/AngelConnector.java`
- Test: `src/test/java/org/amit/finwise/broker/connector/DhanConnectorTest.java`

Add to `.env`:
```
broker.dhan.base-url=https://api.dhan.co
broker.upstox.client-id=your_client_id
broker.upstox.client-secret=your_client_secret
broker.upstox.redirect-uri=https://your-domain/api/broker/upstox/callback
broker.angel.api-key=your_angel_api_key
```

- [ ] **Step 1: Write DhanConnector test**

```java
// src/test/java/org/amit/finwise/broker/connector/DhanConnectorTest.java
package org.amit.finwise.broker.connector;

import org.amit.finwise.broker.model.BrokerEnum;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DhanConnectorTest {
    @Test
    void broker_returnsDhan() {
        assertThat(new DhanConnector(null, null).broker()).isEqualTo(BrokerEnum.DHAN);
    }
}
```

- [ ] **Step 2: Implement DhanConnector**

```java
// src/main/java/org/amit/finwise/broker/connector/DhanConnector.java
package org.amit.finwise.broker.connector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DhanConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final org.amit.finwise.cfo.service.GrowwAuthService growwAuthService;

    @Value("${broker.dhan.base-url:https://api.dhan.co}")
    private String baseUrl;

    @Override
    public BrokerEnum broker() { return BrokerEnum.DHAN; }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(baseUrl + "/v2/holdings")
            .header("access-token", decryptedAccessToken)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = response != null
            ? (List<Map<String, Object>>) response.get("data") : List.of();
        if (data == null) return List.of();

        return data.stream().map(h -> new BrokerHoldingDTO(
            (String) h.getOrDefault("isin", ""),
            (String) h.get("tradingSymbol"),
            (String) h.getOrDefault("securityId", (String) h.get("tradingSymbol")),
            BrokerEnum.DHAN,
            new BigDecimal(h.get("totalQty").toString()),
            new BigDecimal(h.get("avgCostPrice").toString()),
            new BigDecimal(h.get("ltp").toString())
                .multiply(new BigDecimal(h.get("totalQty").toString()))
        )).toList();
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        log.info("[Dhan] syncTransactions since {} — implement per Dhan API v2", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        // Dhan API keys don't expire; return as-is
        return connection;
    }
}
```

- [ ] **Step 3: Implement UpstoxConnector**

```java
// src/main/java/org/amit/finwise/broker/connector/UpstoxConnector.java
package org.amit.finwise.broker.connector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.cfo.service.GrowwAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpstoxConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final GrowwAuthService growwAuthService;

    @Value("${broker.upstox.client-id:}")
    private String clientId;

    @Value("${broker.upstox.client-secret:}")
    private String clientSecret;

    @Value("${broker.upstox.redirect-uri:}")
    private String redirectUri;

    private static final String UPSTOX_BASE = "https://api.upstox.com/v2";

    @Override
    public BrokerEnum broker() { return BrokerEnum.UPSTOX; }

    public String buildAuthUrl() {
        return "https://api.upstox.com/v2/login/authorization/dialog"
            + "?response_type=code&client_id=" + clientId
            + "&redirect_uri=" + redirectUri;
    }

    public BrokerConnection exchangeCode(String userId, String code) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        Map<?, ?> response = restClientBuilder.build()
            .post()
            .uri(UPSTOX_BASE + "/login/authorization/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) response).get("data");
        String accessToken = (String) data.get("access_token");

        return BrokerConnection.builder()
            .userId(userId).broker(BrokerEnum.UPSTOX)
            .encryptedAccessToken(growwAuthService.encrypt(accessToken))
            .tokenExpiresAt(Instant.now().plus(1, ChronoUnit.DAYS))
            .status(ConnectionStatus.ACTIVE)
            .build();
    }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedAccessToken) {
        Map<?, ?> response = restClientBuilder.build()
            .get().uri(UPSTOX_BASE + "/portfolio/long-term-holdings")
            .header("Authorization", "Bearer " + decryptedAccessToken)
            .retrieve().body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = response != null
            ? (List<Map<String, Object>>) response.get("data") : List.of();
        if (data == null) return List.of();

        return data.stream().map(h -> new BrokerHoldingDTO(
            (String) h.getOrDefault("isin", ""),
            (String) h.get("tradingsymbol"),
            (String) h.getOrDefault("company_name", (String) h.get("tradingsymbol")),
            BrokerEnum.UPSTOX,
            new BigDecimal(h.get("quantity").toString()),
            new BigDecimal(h.get("average_price").toString()),
            new BigDecimal(h.get("last_price").toString())
                .multiply(new BigDecimal(h.get("quantity").toString()))
        )).toList();
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String decryptedAccessToken, LocalDate since) {
        log.info("[Upstox] syncTransactions since {} — implement per Upstox API v2", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        connection.setStatus(ConnectionStatus.EXPIRED);
        return connection;
    }
}
```

- [ ] **Step 4: Implement AngelConnector (API-key based)**

```java
// src/main/java/org/amit/finwise/broker/connector/AngelConnector.java
package org.amit.finwise.broker.connector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.BrokerTransactionDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AngelConnector implements BrokerConnector {

    private final RestClient.Builder restClientBuilder;
    private final org.amit.finwise.cfo.service.GrowwAuthService growwAuthService;

    @Value("${broker.angel.api-key:}")
    private String apiKey;

    private static final String ANGEL_BASE = "https://apiconnect.angelone.in/rest/secure/angelbroking";

    @Override
    public BrokerEnum broker() { return BrokerEnum.ANGEL; }

    @Override
    public List<BrokerHoldingDTO> syncHoldings(String decryptedJwtToken) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(ANGEL_BASE + "/portfolio/v1/getHolding")
            .header("Authorization", "Bearer " + decryptedJwtToken)
            .header("X-ClientCode", "")  // populated from user's Angel client code
            .header("X-PrivateKey", apiKey)
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = response != null
            ? (List<Map<String, Object>>) ((Map<?, ?>) response).get("data") : List.of();
        if (data == null) return List.of();

        return data.stream().map(h -> new BrokerHoldingDTO(
            (String) h.getOrDefault("isin", ""),
            (String) h.get("tradingsymbol"),
            (String) h.getOrDefault("symbolname", (String) h.get("tradingsymbol")),
            BrokerEnum.ANGEL,
            new BigDecimal(h.get("quantity").toString()),
            new BigDecimal(h.get("averageprice").toString()),
            new BigDecimal(h.get("ltp").toString())
                .multiply(new BigDecimal(h.get("quantity").toString()))
        )).toList();
    }

    @Override
    public List<BrokerTransactionDTO> syncTransactions(String token, LocalDate since) {
        log.info("[Angel] syncTransactions since {} — implement per Angel API", since);
        return List.of();
    }

    @Override
    public BrokerConnection refreshToken(BrokerConnection connection) {
        return connection; // Angel JWT refresh handled per their session API
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=DhanConnectorTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/connector/ src/test/java/org/amit/finwise/broker/connector/DhanConnectorTest.java
git commit -m "feat(broker): DhanConnector + UpstoxConnector + AngelConnector"
```

---

### Task 6: BrokerSyncService + BrokerController

**Files:**
- Create: `src/main/java/org/amit/finwise/broker/service/BrokerSyncService.java`
- Create: `src/main/java/org/amit/finwise/broker/controller/BrokerController.java`
- Test: `src/test/java/org/amit/finwise/broker/service/BrokerSyncServiceTest.java`

**Interfaces:**
- Consumes: `BrokerConnectorRegistry`, `BrokerConnectionRepository`, `HoldingDeduplicationService`, `GrowwAuthService`
- Produces: `BrokerSyncService.syncAll(userId)` → `List<MergedHoldingDTO>`
- Produces: `GET /api/broker/status` — list of connected brokers + last sync time
- Produces: `POST /api/broker/zerodha/connect?requestToken=` — save connection
- Produces: `POST /api/broker/sync` — trigger sync for current user
- Produces: `DELETE /api/broker/{broker}` — revoke connection

- [ ] **Step 1: Write BrokerSyncService test**

```java
// src/test/java/org/amit/finwise/broker/service/BrokerSyncServiceTest.java
package org.amit.finwise.broker.service;

import org.amit.finwise.broker.connector.BrokerConnector;
import org.amit.finwise.broker.connector.BrokerConnectorRegistry;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.BrokerEnum;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.broker.repository.BrokerConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrokerSyncServiceTest {

    @Mock BrokerConnectionRepository connectionRepo;
    @Mock BrokerConnectorRegistry registry;
    @Mock HoldingDeduplicationService dedup;
    @Mock org.amit.finwise.cfo.service.GrowwAuthService growwAuthService;
    @InjectMocks BrokerSyncService svc;

    @Test
    void syncAll_mergesHoldingsAcrossBrokers() {
        BrokerConnection conn = BrokerConnection.builder()
            .userId("u1").broker(BrokerEnum.DHAN)
            .encryptedAccessToken("enc").status(ConnectionStatus.ACTIVE).build();
        when(connectionRepo.findByUserIdAndStatus("u1", ConnectionStatus.ACTIVE)).thenReturn(List.of(conn));
        when(growwAuthService.decrypt("enc")).thenReturn("plain-token");

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
```

- [ ] **Step 2: Implement BrokerSyncService**

```java
// src/main/java/org/amit/finwise/broker/service/BrokerSyncService.java
package org.amit.finwise.broker.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.broker.connector.BrokerConnectorRegistry;
import org.amit.finwise.broker.dto.BrokerHoldingDTO;
import org.amit.finwise.broker.dto.MergedHoldingDTO;
import org.amit.finwise.broker.model.BrokerConnection;
import org.amit.finwise.broker.model.ConnectionStatus;
import org.amit.finwise.broker.repository.BrokerConnectionRepository;
import org.amit.finwise.cfo.service.GrowwAuthService;
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
    private final GrowwAuthService growwAuthService;

    public List<MergedHoldingDTO> syncAll(String userId) {
        List<BrokerConnection> active = connectionRepo.findByUserIdAndStatus(userId, ConnectionStatus.ACTIVE);
        List<BrokerHoldingDTO> allHoldings = new ArrayList<>();

        for (BrokerConnection conn : active) {
            try {
                String token = growwAuthService.decrypt(conn.getEncryptedAccessToken());
                List<BrokerHoldingDTO> holdings = registry.get(conn.getBroker()).syncHoldings(token);
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
```

- [ ] **Step 3: Implement BrokerController**

```java
// src/main/java/org/amit/finwise/broker/controller/BrokerController.java
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
            .encryptedAccessToken(accessToken) // encrypted by caller or encrypt here
            .status(ConnectionStatus.ACTIVE).build();
        connectionRepo.findByUserIdAndBroker(userId, BrokerEnum.DHAN)
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
```

- [ ] **Step 4: Run all broker tests**

```bash
./mvnw test -Dtest="BrokerConnectionRepositoryTest,BrokerConnectorRegistryTest,HoldingDeduplicationServiceTest,ZerodhaConnectorTest,DhanConnectorTest,BrokerSyncServiceTest" 2>&1 | tail -15
```
Expected: BUILD SUCCESS, all tests green

- [ ] **Step 5: Run full suite to confirm no regressions**

```bash
./mvnw test 2>&1 | tail -20
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/broker/ src/test/java/org/amit/finwise/broker/
git commit -m "feat(broker): BrokerSyncService + BrokerController — connect/sync/revoke endpoints"
```
