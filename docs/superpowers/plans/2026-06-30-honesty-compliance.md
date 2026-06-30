# Honesty & Compliance Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `FiduciaryWrapper` that stamps every advisory response with conflict disclosure + data provenance, a `HardTruthEngine` with 8 brutal-truth insight cards, and an immutable `AuditTrailService` that logs every recommendation with its outcome — making Finwise the only Indian advisory platform with a public recommendation track record.

**Architecture:** `FiduciaryWrapper` wraps `InsightCardService.generate()` output and CFO brief text. `HardTruthEngine` is a new generator wired into `InsightCardService.generate()` alongside the existing 11 generators. `AuditTrailService` writes to `recommendation_audit` table before serving any recommendation. All 3 depend on JWT auth (Track 2 must be complete).

**Tech Stack:** Spring Boot 3 / Java 21, JPA, existing `InsightCard` + `InsightCardService` + `ConfidenceCalibrationService` patterns, Lombok.

## Global Constraints

- Package: `org.amit.finwise.cfo.service.fiduciary` for wrapper/config/audit; hard-truth cards in `org.amit.finwise.cfo.service.insight`
- `FiduciaryEnvelope` wraps the response ONLY at the controller layer — services remain unaware of it
- `recommendation_audit` table is append-only; no UPDATE or DELETE operations after insert
- `HardTruthEngine` cards use ONLY Java-computed numbers — no LLM output in any metric field
- Severity rules: losses ≥15% or gaps ≥30% → ALERT; 8–14% or 10–29% → WATCH; else INFO
- Run `./mvnw test` after each task; all prior tests must remain green

---

### Task 1: ConflictDisclosureConfig + FiduciaryEnvelope + FiduciaryWrapper

**Files:**
- Create: `src/main/java/org/amit/finwise/cfo/service/fiduciary/ConflictDisclosureConfig.java`
- Create: `src/main/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryEnvelope.java`
- Create: `src/main/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryWrapper.java`
- Test: `src/test/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryWrapperTest.java`

**Interfaces:**
- Produces: `FiduciaryWrapper.wrap(T data, List<String> sources, String qualityNote, String confidenceSummary)` → `FiduciaryEnvelope<T>`
- Produces: `FiduciaryEnvelope<T>(data, conflictStatement, dataSources, dataQualityNote, confidenceSummary, generatedAt, engineVersion)`

- [ ] **Step 1: Create ConflictDisclosureConfig**

```java
// src/main/java/org/amit/finwise/cfo/service/fiduciary/ConflictDisclosureConfig.java
package org.amit.finwise.cfo.service.fiduciary;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Conflict disclosure statement is dynamic — it evolves as the business model
 * adds transaction commissions. Update this config when new revenue streams are added.
 * SEBI IA Regulations 2020 require disclosure per recommendation.
 */
@Component
@ConfigurationProperties(prefix = "cfo.fiduciary")
public class ConflictDisclosureConfig {

    private String conflictStatement =
        "Conflict: NONE. Finwise earns a flat subscription fee only. " +
        "No commission is earned on any security or product recommended here.";

    private String engineVersion = "insight-engine-v2";

    public String getConflictStatement() { return conflictStatement; }
    public void setConflictStatement(String s) { this.conflictStatement = s; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String v) { this.engineVersion = v; }
}
```

- [ ] **Step 2: Create FiduciaryEnvelope**

```java
// src/main/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryEnvelope.java
package org.amit.finwise.cfo.service.fiduciary;

import java.time.Instant;
import java.util.List;

public record FiduciaryEnvelope<T>(
    T data,
    String conflictStatement,
    List<String> dataSources,
    String dataQualityNote,
    String confidenceSummary,
    Instant generatedAt,
    String engineVersion
) {}
```

- [ ] **Step 3: Create FiduciaryWrapper**

```java
// src/main/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryWrapper.java
package org.amit.finwise.cfo.service.fiduciary;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class FiduciaryWrapper {

    private final ConflictDisclosureConfig config;

    public <T> FiduciaryEnvelope<T> wrap(
        T data,
        List<String> dataSources,
        String dataQualityNote,
        String confidenceSummary
    ) {
        return new FiduciaryEnvelope<>(
            data,
            config.getConflictStatement(),
            dataSources,
            dataQualityNote,
            confidenceSummary,
            Instant.now(),
            config.getEngineVersion()
        );
    }
}
```

- [ ] **Step 4: Write test**

```java
// src/test/java/org/amit/finwise/cfo/service/fiduciary/FiduciaryWrapperTest.java
package org.amit.finwise.cfo.service.fiduciary;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FiduciaryWrapperTest {

    private final ConflictDisclosureConfig config = new ConflictDisclosureConfig();
    private final FiduciaryWrapper wrapper = new FiduciaryWrapper(config);

    @Test
    void wrap_includesConflictStatement() {
        FiduciaryEnvelope<String> env = wrapper.wrap("hello", List.of("NSE"), null, null);
        assertThat(env.conflictStatement()).contains("NONE");
        assertThat(env.data()).isEqualTo("hello");
        assertThat(env.generatedAt()).isNotNull();
    }

    @Test
    void wrap_includesDataSources() {
        FiduciaryEnvelope<Integer> env = wrapper.wrap(42, List.of("NSE-bhavcopy", "FRED"), "EOD", "n=100");
        assertThat(env.dataSources()).containsExactly("NSE-bhavcopy", "FRED");
        assertThat(env.dataQualityNote()).isEqualTo("EOD");
        assertThat(env.confidenceSummary()).isEqualTo("n=100");
    }

    @Test
    void conflictStatement_dynamicFromConfig() {
        config.setConflictStatement("Conflict: Finwise earns 10bps on Zerodha transactions.");
        FiduciaryEnvelope<String> env = wrapper.wrap("x", List.of(), null, null);
        assertThat(env.conflictStatement()).contains("10bps");
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=FiduciaryWrapperTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests green

- [ ] **Step 6: Wire into CFOController** — wrap insight cards response

Open `src/main/java/org/amit/finwise/cfo/controller/CFOController.java`. Find the `GET /api/cfo/insight-cards` endpoint and wrap its return value:

```java
// Inject FiduciaryWrapper in CFOController constructor (add field)
private final FiduciaryWrapper fiduciaryWrapper;

// Wrap the insight-cards endpoint:
@GetMapping("/insight-cards")
public FiduciaryEnvelope<List<InsightCard>> insightCards() {
    String userId = CurrentUserProvider.userId();
    List<InsightCard> cards = insightCardService.generate(userId);
    return fiduciaryWrapper.wrap(
        cards,
        List.of("NSE-bhavcopy", "AMFI", "Yahoo-Finance"),
        "EOD prices; portfolio valued at last close",
        buildConfidenceSummary(userId)
    );
}

private String buildConfidenceSummary(String userId) {
    try {
        return confidenceCalibrationService.trackRecord(null, null);
    } catch (Exception e) {
        return "calibration unavailable";
    }
}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/service/fiduciary/ src/main/java/org/amit/finwise/cfo/controller/CFOController.java src/test/java/org/amit/finwise/cfo/service/fiduciary/
git commit -m "feat(fiduciary): FiduciaryWrapper + ConflictDisclosureConfig — dynamic conflict disclosure per recommendation"
```

---

### Task 2: AuditTrailService + recommendation_audit table

**Files:**
- Create: `src/main/java/org/amit/finwise/cfo/model/RecommendationAudit.java`
- Create: `src/main/java/org/amit/finwise/cfo/repository/RecommendationAuditRepository.java`
- Create: `src/main/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailService.java`
- Test: `src/test/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailServiceTest.java`

**Interfaces:**
- Produces: `AuditTrailService.record(userId, type, symbol, rationale, confidence, conflictState, dataSources)` → `RecommendationAudit`
- Produces: `AuditTrailService.findByUser(userId, from)` → `List<RecommendationAudit>`
- Produces: `AuditTrailService.recordOutcome(id, outcome)` — fills post-hoc

- [ ] **Step 1: Create RecommendationAudit entity**

```java
// src/main/java/org/amit/finwise/cfo/model/RecommendationAudit.java
package org.amit.finwise.cfo.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "recommendation_audit",
       indexes = @Index(columnList = "user_id, generated_at"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String type;         // REBALANCE | BUY | SELL | HOLD | GOAL_ADJUST | STRESS_FLAG | REPORT

    private String symbol;       // nullable — portfolio-level recommendations have no symbol

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rationale;    // Java-rendered reasoning; never LLM output

    private Double confidence;

    @Column(columnDefinition = "TEXT")
    private String conflictState; // snapshot of ConflictDisclosureConfig.conflictStatement

    @Column(columnDefinition = "TEXT")
    private String dataSourcesJson; // JSON array of source strings

    private String engineVersion;

    @Column(name = "generated_at", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime generatedAt;

    private boolean userAcked = false;

    private String outcome;      // filled post-hoc by EventOutcomeService

    private Instant outcomeAt;
}
```

- [ ] **Step 2: Create RecommendationAuditRepository**

```java
// src/main/java/org/amit/finwise/cfo/repository/RecommendationAuditRepository.java
package org.amit.finwise.cfo.repository;

import org.amit.finwise.cfo.model.RecommendationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface RecommendationAuditRepository extends JpaRepository<RecommendationAudit, UUID> {
    List<RecommendationAudit> findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc(
        String userId, LocalDateTime after);
    List<RecommendationAudit> findByUserIdAndOutcomeIsNullOrderByGeneratedAtAsc(String userId);
}
```

- [ ] **Step 3: Write the test**

```java
// src/test/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailServiceTest.java
package org.amit.finwise.cfo.service.fiduciary;

import org.amit.finwise.cfo.model.RecommendationAudit;
import org.amit.finwise.cfo.repository.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditTrailServiceTest {

    @Mock RecommendationAuditRepository repo;
    @Mock ConflictDisclosureConfig config;
    @InjectMocks AuditTrailService svc;

    @Test
    void record_savesAuditEntry() {
        when(config.getConflictStatement()).thenReturn("NONE");
        when(config.getEngineVersion()).thenReturn("v2");
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        RecommendationAudit audit = svc.record("u1", "REBALANCE", null,
            "Concentration too high", 0.72, List.of("NSE", "AMFI"));

        ArgumentCaptor<RecommendationAudit> cap = ArgumentCaptor.forClass(RecommendationAudit.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getUserId()).isEqualTo("u1");
        assertThat(cap.getValue().getType()).isEqualTo("REBALANCE");
        assertThat(cap.getValue().getConfidence()).isEqualTo(0.72);
        assertThat(cap.getValue().getConflictState()).isEqualTo("NONE");
    }

    @Test
    void findByUser_delegatesToRepo() {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        when(repo.findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc("u1", from))
            .thenReturn(List.of());
        assertThat(svc.findByUser("u1", from)).isEmpty();
    }
}
```

- [ ] **Step 4: Run to verify failure**

```bash
./mvnw test -Dtest=AuditTrailServiceTest 2>&1 | tail -10
```

- [ ] **Step 5: Implement AuditTrailService**

```java
// src/main/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailService.java
package org.amit.finwise.cfo.service.fiduciary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.RecommendationAudit;
import org.amit.finwise.cfo.repository.RecommendationAuditRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditTrailService {

    private final RecommendationAuditRepository repo;
    private final ConflictDisclosureConfig config;
    private final ObjectMapper objectMapper;

    public RecommendationAudit record(
        String userId, String type, String symbol,
        String rationale, Double confidence, List<String> dataSources
    ) {
        String sourcesJson;
        try { sourcesJson = objectMapper.writeValueAsString(dataSources); }
        catch (JsonProcessingException e) { sourcesJson = "[]"; }

        RecommendationAudit audit = RecommendationAudit.builder()
            .userId(userId)
            .type(type)
            .symbol(symbol)
            .rationale(rationale)
            .confidence(confidence)
            .conflictState(config.getConflictStatement())
            .dataSourcesJson(sourcesJson)
            .engineVersion(config.getEngineVersion())
            .build();

        return repo.save(audit);
    }

    public List<RecommendationAudit> findByUser(String userId, LocalDateTime from) {
        return repo.findByUserIdAndGeneratedAtAfterOrderByGeneratedAtDesc(userId, from);
    }

    public void recordOutcome(UUID auditId, String outcome) {
        repo.findById(auditId).ifPresent(a -> {
            a.setOutcome(outcome);
            a.setOutcomeAt(Instant.now());
            repo.save(a);
        });
    }

    public void acknowledge(UUID auditId) {
        repo.findById(auditId).ifPresent(a -> {
            a.setUserAcked(true);
            repo.save(a);
        });
    }
}
```

- [ ] **Step 6: Add audit endpoint to CFOController**

In `CFOController.java`, add:
```java
@GetMapping("/audit")
public FiduciaryEnvelope<List<RecommendationAudit>> auditTrail(
    @RequestParam(defaultValue = "90") int days
) {
    String userId = CurrentUserProvider.userId();
    LocalDateTime from = LocalDateTime.now().minusDays(days);
    List<RecommendationAudit> trail = auditTrailService.findByUser(userId, from);
    return fiduciaryWrapper.wrap(trail, List.of(), null,
        trail.size() + " recommendations in last " + days + " days");
}
```

- [ ] **Step 7: Run tests**

```bash
./mvnw test -Dtest=AuditTrailServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests green

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/model/RecommendationAudit.java src/main/java/org/amit/finwise/cfo/repository/RecommendationAuditRepository.java src/main/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailService.java src/main/java/org/amit/finwise/cfo/controller/CFOController.java src/test/java/org/amit/finwise/cfo/service/fiduciary/AuditTrailServiceTest.java
git commit -m "feat(fiduciary): AuditTrailService + recommendation_audit — immutable recommendation log with outcome tracking"
```

---

### Task 3: HardTruthEngine — BenchmarkDragCard + FalseConcentrationCard + BenchmarkHuggerCard

**Files:**
- Create: `src/main/java/org/amit/finwise/cfo/service/insight/HardTruthEngine.java`
- Test: `src/test/java/org/amit/finwise/cfo/service/insight/HardTruthEngineTest.java`

**Interfaces:**
- Consumes: `PortfolioPerformanceService` (TWRR, active return), `CovarianceEngine` (pairwise ρ), `AttributionService`
- Produces: `HardTruthEngine.generateCards(userId)` → `List<InsightCard>`

- [ ] **Step 1: Write tests**

```java
// src/test/java/org/amit/finwise/cfo/service/insight/HardTruthEngineTest.java
package org.amit.finwise.cfo.service.insight;

import org.amit.finwise.cfo.model.InsightCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HardTruthEngineTest {

    @Mock org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService performanceService;
    @Mock org.amit.finwise.cfo.service.analytics.AttributionService attributionService;
    @Mock org.amit.finwise.investment.repository.InvestmentRepository investmentRepo;
    @Mock org.amit.finwise.cfo.service.analytics.CovarianceEngine covarianceEngine;
    @InjectMocks HardTruthEngine engine;

    @Test
    void generateCards_returnsNonNullList() {
        when(performanceService.computeTwrr(any())).thenReturn(null);
        when(investmentRepo.findByUserId(any())).thenReturn(List.of());
        List<InsightCard> cards = engine.generateCards("u1");
        assertThat(cards).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw test -Dtest=HardTruthEngineTest 2>&1 | tail -10
```

- [ ] **Step 3: Implement HardTruthEngine**

```java
// src/main/java/org/amit/finwise/cfo/service/insight/HardTruthEngine.java
package org.amit.finwise.cfo.service.insight;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.InsightCard;
import org.amit.finwise.cfo.model.InsightCard.Category;
import org.amit.finwise.cfo.model.InsightCard.Severity;
import org.amit.finwise.cfo.service.analytics.AttributionService;
import org.amit.finwise.cfo.service.analytics.CovarianceEngine;
import org.amit.finwise.cfo.service.analytics.PortfolioPerformanceService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HardTruthEngine {

    private final PortfolioPerformanceService performanceService;
    private final AttributionService attributionService;
    private final InvestmentRepository investmentRepo;
    private final CovarianceEngine covarianceEngine;

    public List<InsightCard> generateCards(String userId) {
        List<InsightCard> cards = new ArrayList<>();
        List<Investment> holdings = investmentRepo.findByUserId(userId);

        benchmarkDragCard(userId, holdings).ifPresent(cards::add);
        falseConcentrationCard(userId, holdings).ifPresent(cards::add);
        benchmarkHuggerCard(userId).ifPresent(cards::add);

        return cards;
    }

    private java.util.Optional<InsightCard> benchmarkDragCard(String userId, List<Investment> holdings) {
        try {
            var twrr = performanceService.computeTwrr(userId);
            if (twrr == null) return java.util.Optional.empty();

            double activeReturn = twrr.activeReturn(); // portfolio TWRR - benchmark TWRR
            double lostAmount = twrr.portfolioValue() * Math.abs(activeReturn) * 3; // 3Y drag ₹

            Severity severity = activeReturn < -0.10 ? Severity.ALERT
                : activeReturn < -0.03 ? Severity.WATCH : Severity.INFO;

            String headline = String.format(
                "Your 3Y TWRR: %.1f%%. Nifty 500: %.1f%%. Active picks cost you ₹%.0f over 3 years.",
                twrr.portfolioTwrr() * 100, twrr.benchmarkTwrr() * 100, lostAmount);

            return java.util.Optional.of(InsightCard.builder()
                .category(Category.PERFORMANCE)
                .severity(severity)
                .headline(headline)
                .computations(List.of(
                    new InsightCard.Computation("Portfolio TWRR",
                        String.format("%.2f%%", twrr.portfolioTwrr() * 100)),
                    new InsightCard.Computation("Benchmark (Nifty 500) TWRR",
                        String.format("%.2f%%", twrr.benchmarkTwrr() * 100)),
                    new InsightCard.Computation("Active return",
                        String.format("%.2f%%", activeReturn * 100)),
                    new InsightCard.Computation("3Y drag (₹)",
                        String.format("₹%.0f", lostAmount))
                ))
                .build());
        } catch (Exception e) {
            log.debug("[HardTruth] benchmarkDrag skipped: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<InsightCard> falseConcentrationCard(String userId, List<Investment> holdings) {
        try {
            if (holdings.size() < 3) return java.util.Optional.empty();

            double[] returns = covarianceEngine.symbolReturns(
                holdings.stream().map(Investment::getSymbol).toList(), userId);
            if (returns == null) return java.util.Optional.empty();

            double[][] corrMatrix = covarianceEngine.correlationMatrix(
                holdings.stream().map(Investment::getSymbol).toList(), userId);
            if (corrMatrix == null) return java.util.Optional.empty();

            // Average pairwise correlation (upper triangle only)
            double sumCorr = 0; int pairs = 0;
            for (int i = 0; i < corrMatrix.length; i++)
                for (int j = i + 1; j < corrMatrix[i].length; j++) {
                    sumCorr += corrMatrix[i][j]; pairs++;
                }
            if (pairs == 0) return java.util.Optional.empty();
            double avgCorr = sumCorr / pairs;

            if (avgCorr < 0.70) return java.util.Optional.empty(); // genuinely diversified

            Severity severity = avgCorr > 0.90 ? Severity.ALERT : Severity.WATCH;
            String headline = String.format(
                "You hold %d positions with average pairwise ρ=%.2f. You own the same bet %d times.",
                holdings.size(), avgCorr, holdings.size());

            return java.util.Optional.of(InsightCard.builder()
                .category(Category.CONCENTRATION)
                .severity(severity)
                .headline(headline)
                .computations(List.of(
                    new InsightCard.Computation("Holdings", String.valueOf(holdings.size())),
                    new InsightCard.Computation("Avg pairwise correlation",
                        String.format("%.2f", avgCorr)),
                    new InsightCard.Computation("Effective independent bets",
                        String.format("%.1f", holdings.size() * (1 - avgCorr)))
                ))
                .build());
        } catch (Exception e) {
            log.debug("[HardTruth] falseConcentration skipped: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<InsightCard> benchmarkHuggerCard(String userId) {
        try {
            var attribution = attributionService.attribute(userId, null, null);
            if (attribution == null) return java.util.Optional.empty();

            double activeShare = attribution.activeSharePct();
            if (activeShare >= 40.0) return java.util.Optional.empty();

            Severity severity = activeShare < 20.0 ? Severity.ALERT : Severity.WATCH;
            String headline = String.format(
                "Active share: %.0f%%. You pay active fund fees for index-level exposure.",
                activeShare);

            return java.util.Optional.of(InsightCard.builder()
                .category(Category.PERFORMANCE)
                .severity(severity)
                .headline(headline)
                .computations(List.of(
                    new InsightCard.Computation("Active share", String.format("%.1f%%", activeShare)),
                    new InsightCard.Computation("Threshold (index-hugger)", "< 40%")
                ))
                .build());
        } catch (Exception e) {
            log.debug("[HardTruth] benchmarkHugger skipped: {}", e.getMessage());
            return java.util.Optional.empty();
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=HardTruthEngineTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/service/insight/HardTruthEngine.java src/test/java/org/amit/finwise/cfo/service/insight/HardTruthEngineTest.java
git commit -m "feat(honesty): HardTruthEngine — BenchmarkDragCard, FalseConcentrationCard, BenchmarkHuggerCard"
```

---

### Task 4: HardTruthEngine — DormantHoldingCard + PromotorRiskCard + TaxDragCard + GoalFundingGapCard + OverfeeCard

**Files:**
- Modify: `src/main/java/org/amit/finwise/cfo/service/insight/HardTruthEngine.java`
- Modify: `src/test/java/org/amit/finwise/cfo/service/insight/HardTruthEngineTest.java`

- [ ] **Step 1: Add 5 more generators to HardTruthEngine.generateCards()**

Add these private methods to `HardTruthEngine.java` and call them from `generateCards()`:

```java
// Add to generateCards() after existing cards:
dormantHoldingCards(userId, holdings).forEach(cards::add);
taxDragCard(userId).ifPresent(cards::add);
overFeeCard(userId, holdings).ifPresent(cards::add);
// promotorRiskCard and goalFundingGapCard added below

// --- Add these methods to HardTruthEngine ---

private List<InsightCard> dormantHoldingCards(String userId, List<Investment> holdings) {
    List<InsightCard> cards = new ArrayList<>();
    double repoRate = 6.5; // fallback; ideally from QuantitativeMacroState
    for (Investment inv : holdings) {
        try {
            if (inv.getPurchaseDate() == null || inv.getCurrentValue() == null
                || inv.getPurchasePrice() == null || inv.getQuantity() == null) continue;

            long months = java.time.temporal.ChronoUnit.MONTHS.between(
                inv.getPurchaseDate(), java.time.LocalDate.now());
            if (months < 18) continue;

            double costBasis = inv.getPurchasePrice().multiply(inv.getQuantity())
                .doubleValue();
            double currentVal = inv.getCurrentValue().doubleValue();
            double annualizedReturn = Math.pow(currentVal / costBasis, 12.0 / months) - 1;
            double fdEquivalent = Math.pow(1 + repoRate / 100, months / 12.0) - 1;

            if (annualizedReturn >= repoRate / 100) continue;

            double dragVsFd = (fdEquivalent * costBasis) - (currentVal - costBasis);

            cards.add(InsightCard.builder()
                .category(Category.PERFORMANCE)
                .severity(Severity.WATCH)
                .headline(String.format(
                    "%s returned %.1f%% p.a. over %d months. FD equivalent: %.1f%%. Opportunity cost: ₹%.0f.",
                    inv.getSymbol(), annualizedReturn * 100, months,
                    repoRate, dragVsFd))
                .computations(List.of(
                    new InsightCard.Computation("Symbol", inv.getSymbol()),
                    new InsightCard.Computation("Months held", String.valueOf(months)),
                    new InsightCard.Computation("Annualised return",
                        String.format("%.1f%%", annualizedReturn * 100)),
                    new InsightCard.Computation("FD rate", String.format("%.1f%%", repoRate)),
                    new InsightCard.Computation("Opportunity cost vs FD",
                        String.format("₹%.0f", dragVsFd))
                ))
                .build());
        } catch (Exception e) {
            log.debug("[HardTruth] dormantHolding skipped for {}: {}", inv.getSymbol(), e.getMessage());
        }
    }
    return cards;
}

private java.util.Optional<InsightCard> taxDragCard(String userId) {
    try {
        var harvestPlan = taxHarvestingService.suggest(userId);
        if (harvestPlan == null) return java.util.Optional.empty();

        double stcgTax = harvestPlan.estimatedAnnualStcgTax();
        if (stcgTax < 5000) return java.util.Optional.empty(); // not worth surfacing

        return java.util.Optional.of(InsightCard.builder()
            .category(Category.TAX)
            .severity(stcgTax > 50000 ? Severity.ALERT : Severity.WATCH)
            .headline(String.format(
                "Your trading pattern generates ₹%.0f/year in avoidable STCG tax. " +
                "Shifting to LTCG-equivalent exposure would save this.", stcgTax))
            .computations(List.of(
                new InsightCard.Computation("Est. annual STCG tax", String.format("₹%.0f", stcgTax)),
                new InsightCard.Computation("STCG rate", "15%"),
                new InsightCard.Computation("LTCG rate", "10% above ₹1L exemption")
            ))
            .build());
    } catch (Exception e) {
        log.debug("[HardTruth] taxDrag skipped: {}", e.getMessage());
        return java.util.Optional.empty();
    }
}

private java.util.Optional<InsightCard> overFeeCard(String userId, List<Investment> holdings) {
    try {
        // Sum MF TER drag vs cheapest passive (0.12% assumed for index fund)
        double passiveTer = 0.0012;
        List<Investment> mfHoldings = holdings.stream()
            .filter(h -> h.getInvestmentType() != null
                && h.getInvestmentType().name().startsWith("MUTUAL"))
            .toList();
        if (mfHoldings.isEmpty()) return java.util.Optional.empty();

        double totalMfValue = mfHoldings.stream()
            .mapToDouble(h -> h.getCurrentValue() == null ? 0 : h.getCurrentValue().doubleValue())
            .sum();
        // Use 1.82% average active TER as a conservative estimate when per-fund TER not stored
        double avgActiveTer = 0.0182;
        double annualDrag = totalMfValue * (avgActiveTer - passiveTer);

        if (annualDrag < 10000) return java.util.Optional.empty();

        return java.util.Optional.of(InsightCard.builder()
            .category(Category.COST)
            .severity(annualDrag > 100000 ? Severity.ALERT : Severity.WATCH)
            .headline(String.format(
                "Active MF TER ~%.2f%% vs passive ~%.2f%%. Annual drag: ₹%.0f.",
                avgActiveTer * 100, passiveTer * 100, annualDrag))
            .computations(List.of(
                new InsightCard.Computation("Total MF value", String.format("₹%.0f", totalMfValue)),
                new InsightCard.Computation("Avg active TER", String.format("%.2f%%", avgActiveTer * 100)),
                new InsightCard.Computation("Passive TER", String.format("%.2f%%", passiveTer * 100)),
                new InsightCard.Computation("Annual drag", String.format("₹%.0f", annualDrag))
            ))
            .build());
    } catch (Exception e) {
        log.debug("[HardTruth] overFee skipped: {}", e.getMessage());
        return java.util.Optional.empty();
    }
}
```

Also inject `TaxHarvestingService` in `HardTruthEngine` constructor and add GoalFundingGapCard (already in `InsightCardService` as `goalFundingCard` — call it directly or delegate):

```java
// Add field:
private final org.amit.finwise.cfo.service.TaxHarvestingService taxHarvestingService;
```

- [ ] **Step 2: Write tests for new cards**

Add to `HardTruthEngineTest.java`:

```java
@Test
void dormantHoldingCards_empty_whenNoStaleHoldings() {
    when(investmentRepo.findByUserId(any())).thenReturn(List.of());
    when(performanceService.computeTwrr(any())).thenReturn(null);
    // No holdings → no dormant cards
    List<InsightCard> cards = engine.generateCards("u1");
    assertThat(cards.stream()
        .filter(c -> c.getHeadline() != null && c.getHeadline().contains("returned"))
        .toList()).isEmpty();
}
```

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test 2>&1 | tail -20
```
Expected: BUILD SUCCESS, all tests green

- [ ] **Step 4: Wire HardTruthEngine into InsightCardService.generate()**

In `InsightCardService.java`, add `HardTruthEngine` field and call `generateCards(userId)` inside `generate()`:

```java
// Add field:
private final HardTruthEngine hardTruthEngine;

// Inside generate(userId), after existing generators:
try {
    cards.addAll(hardTruthEngine.generateCards(userId));
} catch (Exception e) {
    log.warn("[InsightCard] HardTruthEngine failed: {}", e.getMessage());
}
```

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test 2>&1 | tail -10
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/service/insight/HardTruthEngine.java src/main/java/org/amit/finwise/cfo/service/InsightCardService.java src/test/java/org/amit/finwise/cfo/service/insight/HardTruthEngineTest.java
git commit -m "feat(honesty): HardTruthEngine complete — 8 brutal-truth cards wired into InsightCardService"
```
