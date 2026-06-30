# Analytics Gap-Fills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 4 remaining analytics gaps: (1) verify and wire GARCH/LVaR, (2) wire live option chain into options analytics, (3) build Company Intelligence View Phase 1 (6 cards at `/api/company/{symbol}`), and (4) upgrade policy engine to hybrid lexical+vector RAG.

**Architecture:** Each gap-fill reuses existing engines — no new math is introduced. GARCH and LiquidityVaR services exist; the task is verification and wiring. Option chain: `NSEOptionChainAdapter` (Data Fabric plan) feeds the existing `OptionChainService`. Company view: `CompanyProfileService` aggregates existing repos into 6 cards. Policy RAG: add pgvector column to `PolicyChunk`, reuse `EmbeddingService`, fuse with existing lexical FTS via RRF.

**Tech Stack:** Spring Boot 3 / Java 21, pgvector (PostgreSQL extension, already available), existing `EmbeddingService`, `LlmRefinementService`, `StockIntelligenceService`, JPA, Lombok.

## Global Constraints

- Package for company view: `org.amit.finwise.company` (already scaffolded)
- Package for policy RAG additions: `org.amit.finwise.policy.service`
- `CompanyProfileService` only reads — it NEVER writes to any repository
- Run `./mvnw test` after each task; all prior tests must remain green
- Commit after each task

---

### Task 1: Verify GARCH + LiquidityVaR are wired and tested

**Files to check:**
- `src/main/java/org/amit/finwise/cfo/service/analytics/GarchService.java`
- `src/main/java/org/amit/finwise/cfo/service/analytics/LiquidityService.java`
- `src/main/java/org/amit/finwise/cfo/service/PortfolioRiskService.java`

**Deliverable:** Both services pass their own tests AND their outputs appear in `PortfolioRiskService.computeRisk()` output.

- [ ] **Step 1: Run existing GARCH/Liquidity tests**

```bash
./mvnw test -Dtest="GarchServiceTest,LiquidityServiceTest" 2>&1 | tail -20
```

If both pass → skip to Step 3. If either fails → fix per the error and re-run.

- [ ] **Step 2: Check if LVaR is surfaced in RiskDecomposition**

```bash
grep -n "liquidityVaR\|lvar\|LiquidityVar\|lvAr" \
  src/main/java/org/amit/finwise/cfo/service/PortfolioRiskService.java | head -20
```

If `liquidityVaR` appears in `RiskDecomposition` population → P9 is already done. If not → continue.

- [ ] **Step 3: Wire LVaR into PortfolioRiskService (only if Step 2 shows it missing)**

In `PortfolioRiskService.java`, inside `computeRisk(userId)` after VaR computation:

```java
// Inject LiquidityService (add field):
private final LiquidityService liquidityService;

// After existing VaR block:
try {
    LiquidityReport liq = liquidityService.compute(userId, var95Parametric);
    // LiquidityVaR = VaR × sqrt(holding period in days)
    // Holding period proxy: assume 5-day liquidation for illiquid names
    double lvar = var95Parametric * Math.sqrt(liq.avgLiquidationDays());
    riskDecompositionBuilder.liquidityVaR(lvar);
    riskDecompositionBuilder.liquidityNote(liq.note());
} catch (Exception e) {
    log.debug("[Risk] LVaR skipped: {}", e.getMessage());
}
```

- [ ] **Step 4: Write a smoke test for LVaR in RiskDecomposition**

```java
// src/test/java/org/amit/finwise/cfo/service/GarchLvarSmokeTest.java
package org.amit.finwise.cfo.service;

import org.amit.finwise.cfo.service.analytics.GarchService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GarchLvarSmokeTest {

    @Test
    void garch_producesFiniteVolForecast() {
        double[] returns = new double[252];
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < returns.length; i++) returns[i] = rng.nextGaussian() * 0.01;

        GarchService svc = new GarchService();
        var forecast = svc.fit(returns);
        assertThat(forecast).isNotNull();
        assertThat(forecast.conditionalVol()).isFinite().isPositive();
    }
}
```

- [ ] **Step 5: Run smoke test**

```bash
./mvnw test -Dtest=GarchLvarSmokeTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/service/PortfolioRiskService.java src/test/java/org/amit/finwise/cfo/service/GarchLvarSmokeTest.java
git commit -m "feat(analytics): verify + wire GARCH/LVaR into PortfolioRiskService — P9 complete"
```

---

### Task 2: Wire live option chain into OptionChainService

**Prerequisite:** `NSEOptionChainAdapter` from Data Fabric plan (Task 6) must exist.

**Files:**
- Modify: `src/main/java/org/amit/finwise/cfo/service/analytics/options/OptionChainService.java`
- Create: `src/main/java/org/amit/finwise/cfo/model/IvTermStructureAlert.java`
- Test: `src/test/java/org/amit/finwise/cfo/service/analytics/options/OptionChainServiceTest.java`

- [ ] **Step 1: Check current OptionChainService state**

```bash
cat -n src/main/java/org/amit/finwise/cfo/service/analytics/options/OptionChainService.java | head -60
```

Note the existing method signatures. The adapter wires in as a new data source — do not remove existing logic.

- [ ] **Step 2: Inject NSEOptionChainAdapter into OptionChainService**

Add field `private final NSEOptionChainAdapter optionChainAdapter;` and a new method:

```java
// In OptionChainService.java:
public DataEnvelope<Map<?, ?>> fetchLiveChain(String symbol) {
    return optionChainAdapter.fetchOptionChain(symbol);
}

/**
 * Detects IV term structure inversion (near-term IV > far-term IV).
 * Inversion signals near-term uncertainty — flag for derivatives holders.
 */
public java.util.Optional<IvTermStructureAlert> detectTermStructureInversion(String symbol) {
    DataEnvelope<Map<?, ?>> envelope = fetchLiveChain(symbol);
    if (!envelope.isPresent()) return java.util.Optional.empty();

    try {
        @SuppressWarnings("unchecked")
        Map<String, Object> filtered = (Map<String, Object>) envelope.value().get("filtered");
        if (filtered == null) return java.util.Optional.empty();

        @SuppressWarnings("unchecked")
        Map<String, Object> ivcurve = (Map<String, Object>) filtered.get("IVP");
        if (ivcurve == null) return java.util.Optional.empty();

        // NSE provides atmIVPct by expiry; compare nearest vs next expiry
        // Structure varies — parse as best-effort
        double nearIv = Double.parseDouble(ivcurve.getOrDefault("atmiv_near", "0").toString());
        double farIv  = Double.parseDouble(ivcurve.getOrDefault("atmiv_far",  "0").toString());

        if (nearIv <= 0 || farIv <= 0) return java.util.Optional.empty();
        if (nearIv <= farIv) return java.util.Optional.empty(); // normal term structure

        return java.util.Optional.of(new IvTermStructureAlert(
            symbol, nearIv, farIv, nearIv - farIv,
            "Near-term IV (" + nearIv + "%) > far-term IV (" + farIv
                + "%). Elevated short-term uncertainty detected."));
    } catch (Exception e) {
        return java.util.Optional.empty();
    }
}
```

- [ ] **Step 3: Create IvTermStructureAlert record**

```java
// src/main/java/org/amit/finwise/cfo/model/IvTermStructureAlert.java
package org.amit.finwise.cfo.model;

public record IvTermStructureAlert(
    String symbol,
    double nearTermIvPct,
    double farTermIvPct,
    double inversionMagnitudePct,
    String interpretation
) {}
```

- [ ] **Step 4: Write test**

```java
// src/test/java/org/amit/finwise/cfo/service/analytics/options/OptionChainServiceTest.java
package org.amit.finwise.cfo.service.analytics.options;

import org.amit.finwise.marketdata.provider.DataEnvelope;
import org.amit.finwise.marketdata.provider.DataQuality;
import org.amit.finwise.marketdata.provider.adapter.NSEOptionChainAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OptionChainServiceTest {

    @Mock NSEOptionChainAdapter adapter;
    @InjectMocks OptionChainService svc;

    @Test
    void detectTermStructureInversion_emptyWhenAdapterMissing() {
        when(adapter.fetchOptionChain("NIFTY"))
            .thenReturn(DataEnvelope.missing("nse-option-chain", "API down"));
        assertThat(svc.detectTermStructureInversion("NIFTY")).isEmpty();
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./mvnw test -Dtest=OptionChainServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/amit/finwise/cfo/service/analytics/options/OptionChainService.java src/main/java/org/amit/finwise/cfo/model/IvTermStructureAlert.java src/test/java/org/amit/finwise/cfo/service/analytics/options/OptionChainServiceTest.java
git commit -m "feat(analytics): wire live NSE option chain into OptionChainService — IV term structure inversion detection"
```

---

### Task 3: Company Intelligence View — CompanyProfileService + /api/company/{symbol}

This is Phase 1 from `COMPANY_VIEW_AND_POLICY_RAG_ROADMAP.md`. Aggregates existing services into 6 beginner-facing cards.

**Files:**
- Create: `src/main/java/org/amit/finwise/company/dto/CompanyProfileResponse.java`
- Create: `src/main/java/org/amit/finwise/company/dto/CorporateActionsCard.java`
- Create: `src/main/java/org/amit/finwise/company/dto/OwnershipCard.java`
- Modify: `src/main/java/org/amit/finwise/company/service/CompanyProfileService.java` (scaffold exists)
- Modify: `src/main/java/org/amit/finwise/company/controller/CompanyProfileController.java` (scaffold exists)
- Test: `src/test/java/org/amit/finwise/company/service/CompanyProfileServiceTest.java`

- [ ] **Step 1: Check what already exists in the company package**

```bash
find src/main/java/org/amit/finwise/company -type f | sort
cat -n src/main/java/org/amit/finwise/company/service/CompanyProfileService.java 2>/dev/null | head -60 || echo "NOT FOUND"
```

Adapt the implementation steps below based on what already exists.

- [ ] **Step 2: Create CompanyProfileResponse and card DTOs**

```java
// src/main/java/org/amit/finwise/company/dto/CompanyProfileResponse.java
package org.amit.finwise.company.dto;

import java.util.List;

public record CompanyProfileResponse(
    String symbol,
    QuoteCard quoteCard,
    CorporateActionsCard corporateActionsCard,
    OwnershipCard ownershipCard,
    Object fundamentalsCard,   // StockDeepDive.fundamentals (existing)
    Object riskCard,           // StockDeepDive.riskMetrics (existing)
    Object newsCatalystsCard   // recent news from NewsAggregatorService (existing)
) {}
```

```java
// src/main/java/org/amit/finwise/company/dto/CorporateActionsCard.java
package org.amit.finwise.company.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CorporateActionsCard(
    List<PastAction> pastActions,
    List<UpcomingEvent> upcomingEvents
) {
    public record PastAction(String type, LocalDate exDate, BigDecimal value, String description) {}
    public record UpcomingEvent(String type, LocalDate eventDate, long daysUntil, String description) {}
}
```

```java
// src/main/java/org/amit/finwise/company/dto/OwnershipCard.java
package org.amit.finwise.company.dto;

import java.math.BigDecimal;
import java.util.List;

public record OwnershipCard(
    BigDecimal promoterHoldingPct,
    BigDecimal promoterPledgedPct,
    BigDecimal fiiHoldingPct,
    BigDecimal diiHoldingPct,
    BigDecimal retailHoldingPct,
    BigDecimal promoterDeltaQoQ,    // quarter-on-quarter change
    BigDecimal fiiDeltaQoQ,
    List<RecentDeal> recentDeals
) {
    public record RecentDeal(String dealType, String party, BigDecimal qty, BigDecimal price, String date) {}
}
```

```java
// src/main/java/org/amit/finwise/company/dto/QuoteCard.java
package org.amit.finwise.company.dto;

import java.math.BigDecimal;

public record QuoteCard(
    BigDecimal lastPrice,
    BigDecimal changePct,
    BigDecimal high52w,
    BigDecimal low52w,
    BigDecimal priceIn52wRangePct,   // 0–100: where in the 52w range is current price
    BigDecimal relativeStrengthVsNifty1m,
    BigDecimal relativeStrengthVsNifty3m,
    String dataQualityNote
) {}
```

- [ ] **Step 3: Write the test**

```java
// src/test/java/org/amit/finwise/company/service/CompanyProfileServiceTest.java
package org.amit.finwise.company.service;

import org.amit.finwise.company.dto.CompanyProfileResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyProfileServiceTest {

    @Mock org.amit.finwise.cfo.service.research.StockIntelligenceService stockIntelligenceService;
    @Mock org.amit.finwise.marketdata.repository.CorporateActionRepository corporateActionRepo;
    @Mock org.amit.finwise.marketdata.repository.CorporateEventRepository corporateEventRepo;
    @Mock org.amit.finwise.marketdata.repository.ShareholdingPatternRepository shareholdingRepo;
    @Mock org.amit.finwise.marketdata.repository.MarketDealRepository marketDealRepo;
    @InjectMocks CompanyProfileService svc;

    @Test
    void getProfile_returnsNonNull() {
        when(stockIntelligenceService.analyze(any(), any())).thenReturn(null);
        when(corporateActionRepo.findBySymbolOrderByExDateDesc(any())).thenReturn(java.util.List.of());
        when(corporateEventRepo.findBySymbolAndEventDateAfterOrderByEventDateAsc(any(), any()))
            .thenReturn(java.util.List.of());
        when(shareholdingRepo.findTopBySymbolOrderByAsOfDateDesc(any())).thenReturn(java.util.Optional.empty());
        when(marketDealRepo.findBySymbolOrderByDealDateDesc(any())).thenReturn(java.util.List.of());

        CompanyProfileResponse profile = svc.getProfile("TCS", "u1");
        assertThat(profile).isNotNull();
        assertThat(profile.symbol()).isEqualTo("TCS");
    }
}
```

- [ ] **Step 4: Run to verify failure**

```bash
./mvnw test -Dtest=CompanyProfileServiceTest 2>&1 | tail -10
```

- [ ] **Step 5: Implement CompanyProfileService**

```java
// src/main/java/org/amit/finwise/company/service/CompanyProfileService.java
package org.amit.finwise.company.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.company.dto.*;
import org.amit.finwise.marketdata.model.CorporateAction;
import org.amit.finwise.marketdata.model.CorporateEvent;
import org.amit.finwise.marketdata.model.MarketDeal;
import org.amit.finwise.marketdata.model.ShareholdingPattern;
import org.amit.finwise.marketdata.repository.*;
import org.amit.finwise.cfo.service.research.StockIntelligenceService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyProfileService {

    private final StockIntelligenceService stockIntelligenceService;
    private final CorporateActionRepository corporateActionRepo;
    private final CorporateEventRepository corporateEventRepo;
    private final ShareholdingPatternRepository shareholdingRepo;
    private final MarketDealRepository marketDealRepo;

    public CompanyProfileResponse getProfile(String symbol, String userId) {
        // Card 3: Ownership (BSE XBRL data already in DB from marketdata ingestion)
        OwnershipCard ownershipCard = buildOwnershipCard(symbol);

        // Card 2: Corporate actions + forward calendar
        CorporateActionsCard actionsCard = buildCorporateActionsCard(symbol);

        // Cards 1, 4, 5, 6: from StockIntelligenceService (existing)
        Object deepDive = null;
        QuoteCard quoteCard = null;
        try {
            var dive = stockIntelligenceService.analyze(symbol, userId);
            if (dive != null) {
                deepDive = dive;
                quoteCard = buildQuoteCard(dive);
            }
        } catch (Exception e) {
            log.warn("[CompanyProfile] StockIntelligence failed for {}: {}", symbol, e.getMessage());
        }

        return new CompanyProfileResponse(
            symbol, quoteCard, actionsCard, ownershipCard,
            deepDive != null ? ((org.amit.finwise.cfo.dto.StockDeepDive) deepDive).fundamentals() : null,
            deepDive != null ? ((org.amit.finwise.cfo.dto.StockDeepDive) deepDive).riskMetrics() : null,
            null // news card wired in next iteration
        );
    }

    private CorporateActionsCard buildCorporateActionsCard(String symbol) {
        List<CorporateAction> past = corporateActionRepo
            .findBySymbolOrderByExDateDesc(symbol);
        List<CorporateEvent> upcoming = corporateEventRepo
            .findBySymbolAndEventDateAfterOrderByEventDateAsc(symbol, LocalDate.now());

        List<CorporateActionsCard.PastAction> pastMapped = past.stream()
            .limit(10)
            .map(a -> new CorporateActionsCard.PastAction(
                a.getActionType(), a.getExDate(), a.getValue(), a.getDescription()))
            .toList();

        List<CorporateActionsCard.UpcomingEvent> upcomingMapped = upcoming.stream()
            .limit(5)
            .map(e -> new CorporateActionsCard.UpcomingEvent(
                e.getEventType(), e.getEventDate(),
                ChronoUnit.DAYS.between(LocalDate.now(), e.getEventDate()),
                e.getDescription()))
            .toList();

        return new CorporateActionsCard(pastMapped, upcomingMapped);
    }

    private OwnershipCard buildOwnershipCard(String symbol) {
        Optional<ShareholdingPattern> latest = shareholdingRepo
            .findTopBySymbolOrderByAsOfDateDesc(symbol);
        List<MarketDeal> deals = marketDealRepo
            .findBySymbolOrderByDealDateDesc(symbol).stream().limit(5).toList();

        if (latest.isEmpty()) {
            return new OwnershipCard(null, null, null, null, null, null, null,
                deals.stream().map(d -> new OwnershipCard.RecentDeal(
                    d.getDealType(), d.getClientName(), d.getQuantity(),
                    d.getPrice(), d.getDealDate().toString())).toList());
        }

        ShareholdingPattern sp = latest.get();
        return new OwnershipCard(
            sp.getPromoterPct(), sp.getPromoterPledgedPct(),
            sp.getFiiPct(), sp.getDiiPct(), sp.getRetailPct(),
            sp.getPromoterDeltaQoQ(), sp.getFiiDeltaQoQ(),
            deals.stream().map(d -> new OwnershipCard.RecentDeal(
                d.getDealType(), d.getClientName(), d.getQuantity(),
                d.getPrice(), d.getDealDate().toString())).toList());
    }

    private QuoteCard buildQuoteCard(org.amit.finwise.cfo.dto.StockDeepDive dive) {
        if (dive.quote() == null) return null;
        BigDecimal last = dive.quote().lastPrice();
        BigDecimal h52 = dive.quote().high52w();
        BigDecimal l52 = dive.quote().low52w();
        BigDecimal rangePct = (h52 != null && l52 != null && h52.compareTo(l52) != 0)
            ? last.subtract(l52).divide(h52.subtract(l52), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
            : null;
        return new QuoteCard(last, dive.quote().changePct(), h52, l52,
            rangePct, null, null, "EOD close");
    }
}
```

- [ ] **Step 6: Wire CompanyProfileController**

```java
// src/main/java/org/amit/finwise/company/controller/CompanyProfileController.java
package org.amit.finwise.company.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.auth.CurrentUserProvider;
import org.amit.finwise.company.dto.CompanyProfileResponse;
import org.amit.finwise.company.service.CompanyProfileService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyProfileController {

    private final CompanyProfileService profileService;

    @GetMapping("/{symbol}")
    public CompanyProfileResponse getProfile(@PathVariable String symbol) {
        return profileService.getProfile(symbol.toUpperCase(), CurrentUserProvider.userId());
    }
}
```

- [ ] **Step 7: Run tests**

```bash
./mvnw test -Dtest=CompanyProfileServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 8: Run full suite**

```bash
./mvnw test 2>&1 | tail -10
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/amit/finwise/company/ src/test/java/org/amit/finwise/company/
git commit -m "feat(company): CompanyProfileService — 6-card company intelligence view at /api/company/{symbol}"
```

---

### Task 4: Policy RAG — pgvector embeddings on PolicyChunk

**Prerequisite:** pgvector PostgreSQL extension must be enabled: `CREATE EXTENSION IF NOT EXISTS vector;`

**Files:**
- Modify: `src/main/java/org/amit/finwise/policy/model/PolicyChunk.java`
- Create: `src/main/java/org/amit/finwise/policy/service/PolicyChunkEmbeddingService.java`
- Test: `src/test/java/org/amit/finwise/policy/service/PolicyChunkEmbeddingServiceTest.java`

- [ ] **Step 1: Enable pgvector in the DB**

Run this once in your PostgreSQL instance:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Verify:
```sql
SELECT * FROM pg_extension WHERE extname = 'vector';
```

- [ ] **Step 2: Add embedding column to PolicyChunk**

In `PolicyChunk.java`, add:

```java
// Add import:
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Add field:
@Column(columnDefinition = "vector(1536)")  // dimension matches EmbeddingService provider
@JdbcTypeCode(SqlTypes.VECTOR)
private float[] embedding;
```

Also add getter/setter (Lombok `@Data` handles it if already annotated).

- [ ] **Step 3: Write the test**

```java
// src/test/java/org/amit/finwise/policy/service/PolicyChunkEmbeddingServiceTest.java
package org.amit.finwise.policy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyChunkEmbeddingServiceTest {

    @Mock org.amit.finwise.policy.repository.PolicyChunkRepository chunkRepo;
    @Mock org.amit.finwise.cfo.service.EmbeddingService embeddingService;
    @InjectMocks PolicyChunkEmbeddingService svc;

    @Test
    void embedAll_callsEmbeddingForEachChunk() {
        org.amit.finwise.policy.model.PolicyChunk chunk = new org.amit.finwise.policy.model.PolicyChunk();
        chunk.setChunkText("RBI raises CRR by 50bps effective April 1.");
        when(chunkRepo.findByEmbeddingIsNull()).thenReturn(List.of(chunk));
        when(embeddingService.embed(any())).thenReturn(new float[1536]);
        when(chunkRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.embedAll();
        verify(embeddingService).embed("RBI raises CRR by 50bps effective April 1.");
        verify(chunkRepo).save(any());
    }
}
```

- [ ] **Step 4: Run to verify failure**

```bash
./mvnw test -Dtest=PolicyChunkEmbeddingServiceTest 2>&1 | tail -10
```

- [ ] **Step 5: Implement PolicyChunkEmbeddingService**

```java
// src/main/java/org/amit/finwise/policy/service/PolicyChunkEmbeddingService.java
package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyChunkEmbeddingService {

    private final PolicyChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;

    @Async
    public void embedAll() {
        List<PolicyChunk> unembedded = chunkRepo.findByEmbeddingIsNull();
        log.info("[PolicyEmbedding] Embedding {} chunks", unembedded.size());
        for (PolicyChunk chunk : unembedded) {
            try {
                float[] embedding = embeddingService.embed(chunk.getChunkText());
                chunk.setEmbedding(embedding);
                chunkRepo.save(chunk);
            } catch (Exception e) {
                log.warn("[PolicyEmbedding] Failed for chunk {}: {}", chunk.getId(), e.getMessage());
            }
        }
        log.info("[PolicyEmbedding] Done");
    }
}
```

Also add to `PolicyChunkRepository`:
```java
List<PolicyChunk> findByEmbeddingIsNull();
```

- [ ] **Step 6: Call embedAll() after document ingest**

In `PolicyIntelligenceService.ingestDocument()`, after chunking:
```java
policyChunkEmbeddingService.embedAll(); // async — non-blocking
```

- [ ] **Step 7: Run tests**

```bash
./mvnw test -Dtest=PolicyChunkEmbeddingServiceTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/policy/ src/test/java/org/amit/finwise/policy/
git commit -m "feat(policy-rag): pgvector embeddings on PolicyChunk — async embedding on ingest"
```

---

### Task 5: Policy RAG — PolicyHybridRetriever (RRF fusion)

**Files:**
- Create: `src/main/java/org/amit/finwise/policy/service/PolicyHybridRetriever.java`
- Modify: `src/main/java/org/amit/finwise/policy/service/PolicyIntelligenceService.java`
- Test: `src/test/java/org/amit/finwise/policy/service/PolicyHybridRetrieverTest.java`

**Interfaces:**
- Produces: `PolicyHybridRetriever.retrieve(query, userId, topK)` → `List<PolicyChunk>` (RRF-fused)
- Replaces: `PolicySearchIndexService.search()` as the primary retrieval path; lexical kept as fallback

- [ ] **Step 1: Add vector search query to PolicyChunkRepository**

```java
// In PolicyChunkRepository, add native query:
@Query(value = "SELECT * FROM policy_chunks ORDER BY embedding <=> CAST(:queryVec AS vector) LIMIT :limit",
       nativeQuery = true)
List<PolicyChunk> findByVectorSimilarity(
    @Param("queryVec") String queryVec,  // pgvector accepts array literal '[0.1,0.2,...]'
    @Param("limit") int limit);
```

- [ ] **Step 2: Write the test**

```java
// src/test/java/org/amit/finwise/policy/service/PolicyHybridRetrieverTest.java
package org.amit.finwise.policy.service;

import org.amit.finwise.policy.model.PolicyChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PolicyHybridRetrieverTest {

    @Mock PolicySearchIndexService lexicalSearch;
    @Mock org.amit.finwise.policy.repository.PolicyChunkRepository chunkRepo;
    @Mock org.amit.finwise.cfo.service.EmbeddingService embeddingService;
    @InjectMocks PolicyHybridRetriever retriever;

    @Test
    void retrieve_mergesLexicalAndVectorResults() {
        PolicyChunk c1 = new PolicyChunk(); c1.setId(1L); c1.setChunkText("repo rate");
        PolicyChunk c2 = new PolicyChunk(); c2.setId(2L); c2.setChunkText("CRR");
        when(lexicalSearch.search(any(), anyInt())).thenReturn(List.of(c1));
        when(embeddingService.embed(any())).thenReturn(new float[1536]);
        when(chunkRepo.findByVectorSimilarity(any(), anyInt())).thenReturn(List.of(c2));

        List<PolicyChunk> results = retriever.retrieve("monetary policy", "u1", 5);
        assertThat(results).isNotEmpty();
    }

    @Test
    void retrieve_fallsBackToLexicalWhenVectorFails() {
        PolicyChunk c1 = new PolicyChunk(); c1.setId(1L);
        when(lexicalSearch.search(any(), anyInt())).thenReturn(List.of(c1));
        when(embeddingService.embed(any())).thenThrow(new RuntimeException("embedding down"));

        List<PolicyChunk> results = retriever.retrieve("inflation", "u1", 5);
        assertThat(results).isNotEmpty(); // lexical fallback
    }
}
```

- [ ] **Step 3: Run to verify failure**

```bash
./mvnw test -Dtest=PolicyHybridRetrieverTest 2>&1 | tail -10
```

- [ ] **Step 4: Implement PolicyHybridRetriever**

```java
// src/main/java/org/amit/finwise/policy/service/PolicyHybridRetriever.java
package org.amit.finwise.policy.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.EmbeddingService;
import org.amit.finwise.policy.model.PolicyChunk;
import org.amit.finwise.policy.repository.PolicyChunkRepository;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyHybridRetriever {

    private final PolicySearchIndexService lexicalSearch;
    private final PolicyChunkRepository chunkRepo;
    private final EmbeddingService embeddingService;

    private static final int RRF_K = 60;

    public List<PolicyChunk> retrieve(String query, String userId, int topK) {
        // Lexical FTS — always available
        List<PolicyChunk> lexical = fetchLexical(query, topK * 2);

        // Vector — optional; degrade to lexical-only if embedding fails
        List<PolicyChunk> vector = fetchVector(query, topK * 2);

        if (vector.isEmpty()) return lexical.stream().limit(topK).toList();

        // Reciprocal Rank Fusion: score = Σ 1 / (k + rank_i)
        Map<Long, Double> rrfScores = new HashMap<>();

        IntStream.range(0, lexical.size()).forEach(i -> {
            Long id = lexical.get(i).getId();
            rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        });
        IntStream.range(0, vector.size()).forEach(i -> {
            Long id = vector.get(i).getId();
            rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        });

        // Build index of all chunks
        Map<Long, PolicyChunk> allChunks = new HashMap<>();
        lexical.forEach(c -> allChunks.put(c.getId(), c));
        vector.forEach(c -> allChunks.put(c.getId(), c));

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            .limit(topK)
            .map(e -> allChunks.get(e.getKey()))
            .filter(Objects::nonNull)
            .toList();
    }

    private List<PolicyChunk> fetchLexical(String query, int limit) {
        try { return lexicalSearch.search(query, limit); }
        catch (Exception e) {
            log.warn("[PolicyHybrid] Lexical search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<PolicyChunk> fetchVector(String query, int limit) {
        try {
            float[] embedding = embeddingService.embed(query);
            String pgVec = Arrays.toString(embedding)
                .replace('[', '[').replace(']', ']'); // pgvector literal format
            return chunkRepo.findByVectorSimilarity(pgVec, limit);
        } catch (Exception e) {
            log.debug("[PolicyHybrid] Vector search failed (fallback to lexical): {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 5: Replace search() calls in PolicyIntelligenceService**

In `PolicyIntelligenceService.java`, replace calls to `policySearchIndexService.search(query, limit)` with `policyHybridRetriever.retrieve(query, userId, limit)`. Inject `PolicyHybridRetriever` as a field.

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=PolicyHybridRetrieverTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests green

- [ ] **Step 7: Run full suite**

```bash
./mvnw test 2>&1 | tail -10
```
Expected: BUILD SUCCESS, all tests green

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/policy/ src/test/java/org/amit/finwise/policy/
git commit -m "feat(policy-rag): PolicyHybridRetriever — RRF fusion of lexical FTS + pgvector similarity"
```
