# Data Fabric — MarketDataProvider Interface + Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `MarketDataProvider` strategy layer with Resilience4j circuit breakers per adapter and 10 new adapters covering free global macro (FRED), BSE filings, NSE announcements, SEBI insider trades, live option chain, real-time quotes (Zerodha/Dhan), deep fundamentals (Screener), and RBI macro — so the analytics engine can swap data sources post-funding without code changes.

**Architecture:** `MarketDataProvider` interface mirrors `LLMProvider` exactly. `MarketDataRouter` selects the healthiest adapter per `DataCapability`. Every value returned is wrapped in `DataEnvelope<T>` carrying source, timestamp, and quality enum. Resilience4j `CircuitBreaker` per adapter trips after 3 failures, reopens after 60s.

**Tech Stack:** Spring Boot 3 / Java 21, RestClient, Resilience4j (already on classpath via spring-boot-starter), Jackson, Lombok.

## Global Constraints

- Package: `org.amit.finwise.marketdata.provider` for interface + router; `org.amit.finwise.marketdata.provider.adapter` for each adapter
- Analytics engine (`cfo/`, `investment/`, `goal/`) MUST NOT import any concrete adapter class — only `MarketDataRouter`
- `DataEnvelope.quality == MISSING` → adapter returns empty envelope, never throws NPE to caller
- Run `./mvnw test` after each task; 281+ pre-existing tests must remain green
- Commit after each task

---

### Task 1: MarketDataProvider interface + DataEnvelope + DataCapability + DataQuality

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/DataCapability.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/DataQuality.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/DataEnvelope.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/MarketDataProvider.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/DataEnvelopeTest.java`

**Interfaces:**
- Produces: `DataEnvelope<T>(value, source, fetchedAt, quality, fallbackNote)`
- Produces: `MarketDataProvider.name()`, `supports(DataCapability)`, `isHealthy()`

- [ ] **Step 1: Create DataCapability enum**

```java
// src/main/java/org/amit/finwise/marketdata/provider/DataCapability.java
package org.amit.finwise.marketdata.provider;

public enum DataCapability {
    REAL_TIME_QUOTE,
    EOD_PRICE,
    HISTORICAL_OHLCV,
    FUNDAMENTALS,
    OPTION_CHAIN,
    PORTFOLIO_SYNC,
    MACRO_GLOBAL,       // FRED: DXY, crude, VIX, Fed rate
    MACRO_INDIA,        // RBI DBIE: repo, CRR, credit growth
    CORPORATE_FILINGS,  // BSE XBRL: promoter pledge, shareholding
    ANNOUNCEMENTS,      // NSE: board meetings, results calendar
    INSIDER_TRADES,     // SEBI disclosures
    WORLD_BANK          // GDP, CPI, current account
}
```

- [ ] **Step 2: Create DataQuality enum**

```java
// src/main/java/org/amit/finwise/marketdata/provider/DataQuality.java
package org.amit.finwise.marketdata.provider;

public enum DataQuality {
    LIVE,       // real-time, <1 min stale
    EOD,        // end-of-day close
    ESTIMATED,  // modelled or interpolated
    STALE,      // fetched >24h ago
    MISSING     // unavailable; fallbackNote explains why
}
```

- [ ] **Step 3: Create DataEnvelope record**

```java
// src/main/java/org/amit/finwise/marketdata/provider/DataEnvelope.java
package org.amit.finwise.marketdata.provider;

import java.time.Instant;
import java.util.Optional;

public record DataEnvelope<T>(
    T value,
    String source,
    Instant fetchedAt,
    DataQuality quality,
    String fallbackNote
) {
    public static <T> DataEnvelope<T> of(T value, String source, DataQuality quality) {
        return new DataEnvelope<>(value, source, Instant.now(), quality, null);
    }

    public static <T> DataEnvelope<T> missing(String source, String reason) {
        return new DataEnvelope<>(null, source, Instant.now(), DataQuality.MISSING, reason);
    }

    public Optional<T> valueOpt() {
        return Optional.ofNullable(value);
    }

    public boolean isPresent() { return value != null; }
}
```

- [ ] **Step 4: Create MarketDataProvider interface**

```java
// src/main/java/org/amit/finwise/marketdata/provider/MarketDataProvider.java
package org.amit.finwise.marketdata.provider;

public interface MarketDataProvider {
    String name();
    boolean supports(DataCapability capability);
    boolean isHealthy();
}
```

- [ ] **Step 5: Write DataEnvelope tests**

```java
// src/test/java/org/amit/finwise/marketdata/provider/DataEnvelopeTest.java
package org.amit.finwise.marketdata.provider;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DataEnvelopeTest {

    @Test
    void of_isPresent() {
        DataEnvelope<Double> e = DataEnvelope.of(3.14, "test", DataQuality.LIVE);
        assertThat(e.isPresent()).isTrue();
        assertThat(e.valueOpt()).contains(3.14);
    }

    @Test
    void missing_isNotPresent() {
        DataEnvelope<Double> e = DataEnvelope.missing("test", "API down");
        assertThat(e.isPresent()).isFalse();
        assertThat(e.quality()).isEqualTo(DataQuality.MISSING);
        assertThat(e.fallbackNote()).isEqualTo("API down");
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=DataEnvelopeTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 2 tests green

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/ src/test/java/org/amit/finwise/marketdata/provider/
git commit -m "feat(data-fabric): MarketDataProvider interface + DataEnvelope + DataCapability"
```

---

### Task 2: MarketDataRouter with Resilience4j circuit breakers

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/MarketDataRouter.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/MarketDataRouterTest.java`

**Interfaces:**
- Consumes: `List<MarketDataProvider>`, `CircuitBreakerRegistry`
- Produces: `MarketDataRouter.healthyProvider(DataCapability)` → `Optional<MarketDataProvider>`
- Produces: `MarketDataRouter.isHealthy(String providerName)` — delegates to circuit breaker

- [ ] **Step 1: Write the test**

```java
// src/test/java/org/amit/finwise/marketdata/provider/MarketDataRouterTest.java
package org.amit.finwise.marketdata.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataRouterTest {

    @Test
    void healthyProvider_returnsFirstSupportingAdapter() {
        MarketDataProvider fred = new MarketDataProvider() {
            public String name() { return "fred"; }
            public boolean supports(DataCapability c) { return c == DataCapability.MACRO_GLOBAL; }
            public boolean isHealthy() { return true; }
        };
        MarketDataRouter router = new MarketDataRouter(List.of(fred), CircuitBreakerRegistry.ofDefaults());
        Optional<MarketDataProvider> found = router.healthyProvider(DataCapability.MACRO_GLOBAL);
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("fred");
    }

    @Test
    void healthyProvider_returnsEmptyWhenNoneSupport() {
        MarketDataRouter router = new MarketDataRouter(List.of(), CircuitBreakerRegistry.ofDefaults());
        assertThat(router.healthyProvider(DataCapability.REAL_TIME_QUOTE)).isEmpty();
    }

    @Test
    void healthyProvider_skipsUnhealthyAdapter() {
        MarketDataProvider unhealthy = new MarketDataProvider() {
            public String name() { return "broken"; }
            public boolean supports(DataCapability c) { return true; }
            public boolean isHealthy() { return false; }
        };
        MarketDataRouter router = new MarketDataRouter(List.of(unhealthy), CircuitBreakerRegistry.ofDefaults());
        assertThat(router.healthyProvider(DataCapability.MACRO_GLOBAL)).isEmpty();
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
./mvnw test -Dtest=MarketDataRouterTest 2>&1 | tail -10
```

- [ ] **Step 3: Implement MarketDataRouter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/MarketDataRouter.java
package org.amit.finwise.marketdata.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class MarketDataRouter {

    private final List<MarketDataProvider> providers;
    private final CircuitBreakerRegistry cbRegistry;

    public MarketDataRouter(List<MarketDataProvider> providers, CircuitBreakerRegistry cbRegistry) {
        this.providers = providers;
        this.cbRegistry = cbRegistry;
    }

    public Optional<MarketDataProvider> healthyProvider(DataCapability capability) {
        return providers.stream()
            .filter(p -> p.supports(capability))
            .filter(MarketDataProvider::isHealthy)
            .filter(p -> cbRegistry.circuitBreaker(p.name()).getState()
                != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN)
            .findFirst();
    }

    public <T> DataEnvelope<T> route(DataCapability capability, java.util.function.Function<MarketDataProvider, DataEnvelope<T>> fetcher) {
        return healthyProvider(capability)
            .map(p -> {
                try {
                    return cbRegistry.circuitBreaker(p.name())
                        .executeSupplier(() -> fetcher.apply(p));
                } catch (Exception e) {
                    log.warn("[MarketDataRouter] {} failed for {}: {}", p.name(), capability, e.getMessage());
                    return DataEnvelope.<T>missing(p.name(), e.getMessage());
                }
            })
            .orElseGet(() -> DataEnvelope.missing("none", "No healthy provider for " + capability));
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=MarketDataRouterTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/MarketDataRouter.java src/test/java/org/amit/finwise/marketdata/provider/MarketDataRouterTest.java
git commit -m "feat(data-fabric): MarketDataRouter with Resilience4j circuit breaker routing"
```

---

### Task 3: FREDMacroAdapter — global macro (DXY, crude, VIX, Fed rate, gold)

FRED API is free, no auth required. Base URL: `https://api.stlouisfed.org/fred/series/observations`.

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/FREDMacroAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/GlobalMacroSnapshot.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/adapter/FREDMacroAdapterTest.java`

Add to `.env`:
```
market.fred.api-key=your_fred_api_key  # free at fred.stlouisfed.org
```

- [ ] **Step 1: Create GlobalMacroSnapshot**

```java
// src/main/java/org/amit/finwise/marketdata/provider/GlobalMacroSnapshot.java
package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;

public record GlobalMacroSnapshot(
    BigDecimal fedFundsRate,      // FEDFUNDS series
    BigDecimal dxy,               // DTWEXBGS (trade-weighted USD)
    BigDecimal crudePriceWti,     // DCOILWTICO
    BigDecimal goldPriceUsd,      // GOLDAMGBD228NLBM
    BigDecimal usVix,             // VIXCLS
    BigDecimal usTenYearYield,    // DGS10
    String dataDate               // latest observation date
) {}
```

- [ ] **Step 2: Write test with mock HTTP**

```java
// src/test/java/org/amit/finwise/marketdata/provider/adapter/FREDMacroAdapterTest.java
package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.amit.finwise.marketdata.provider.DataEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class FREDMacroAdapterTest {

    @Test
    void name_returnsFred() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        assertThat(adapter.name()).isEqualTo("fred");
    }

    @Test
    void supports_macroGlobalOnly() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        assertThat(adapter.supports(DataCapability.MACRO_GLOBAL)).isTrue();
        assertThat(adapter.supports(DataCapability.REAL_TIME_QUOTE)).isFalse();
    }

    @Test
    void isHealthy_falseWhenApiKeyBlank() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        ReflectionTestUtils.setField(adapter, "apiKey", "");
        assertThat(adapter.isHealthy()).isFalse();
    }

    @Test
    void isHealthy_trueWhenApiKeySet() {
        FREDMacroAdapter adapter = new FREDMacroAdapter(null);
        ReflectionTestUtils.setField(adapter, "apiKey", "abc123");
        assertThat(adapter.isHealthy()).isTrue();
    }
}
```

- [ ] **Step 3: Implement FREDMacroAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/FREDMacroAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class FREDMacroAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Value("${market.fred.api-key:}")
    private String apiKey;

    private static final String BASE = "https://api.stlouisfed.org/fred/series/observations";

    @Override public String name() { return "fred"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.MACRO_GLOBAL; }
    @Override public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }

    public DataEnvelope<GlobalMacroSnapshot> fetchGlobalMacro() {
        try {
            BigDecimal fed = fetchSeries("FEDFUNDS");
            BigDecimal dxy = fetchSeries("DTWEXBGS");
            BigDecimal crude = fetchSeries("DCOILWTICO");
            BigDecimal gold = fetchSeries("GOLDAMGBD228NLBM");
            BigDecimal vix = fetchSeries("VIXCLS");
            BigDecimal us10y = fetchSeries("DGS10");
            GlobalMacroSnapshot snap = new GlobalMacroSnapshot(fed, dxy, crude, gold, vix, us10y, "latest");
            return DataEnvelope.of(snap, name(), DataQuality.EOD);
        } catch (Exception e) {
            log.error("[FRED] fetch failed: {}", e.getMessage());
            return DataEnvelope.missing(name(), "FRED API error: " + e.getMessage());
        }
    }

    private BigDecimal fetchSeries(String seriesId) {
        Map<?, ?> response = restClientBuilder.build()
            .get()
            .uri(uriBuilder -> uriBuilder
                .scheme("https").host("api.stlouisfed.org")
                .path("/fred/series/observations")
                .queryParam("series_id", seriesId)
                .queryParam("api_key", apiKey)
                .queryParam("file_type", "json")
                .queryParam("sort_order", "desc")
                .queryParam("limit", "1")
                .build())
            .retrieve()
            .body(Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> observations = (List<Map<String, String>>) response.get("observations");
        if (observations == null || observations.isEmpty()) return null;
        String value = observations.get(0).get("value");
        return ".".equals(value) ? null : new BigDecimal(value);
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=FREDMacroAdapterTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 4 tests green

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/ src/test/java/org/amit/finwise/marketdata/provider/adapter/FREDMacroAdapterTest.java
git commit -m "feat(data-fabric): FREDMacroAdapter — DXY, crude, VIX, Fed rate, gold"
```

---

### Task 4: BSEFilingsAdapter — promoter pledging + shareholding patterns

BSE XBRL data is available at `https://www.bseindia.com/corporates/Shareholding_Patterns.aspx` and via structured API. We parse the quarterly shareholding XML/JSON.

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/PromoterFilingSnapshot.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapterTest.java`

- [ ] **Step 1: Create PromoterFilingSnapshot**

```java
// src/main/java/org/amit/finwise/marketdata/provider/PromoterFilingSnapshot.java
package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;

public record PromoterFilingSnapshot(
    String symbol,
    String quarterEndDate,
    BigDecimal promoterHoldingPct,
    BigDecimal promoterPledgedPct,   // pledged as % of promoter holding
    BigDecimal fiiHoldingPct,
    BigDecimal diiHoldingPct,
    BigDecimal retailHoldingPct
) {}
```

- [ ] **Step 2: Write test**

```java
// src/test/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapterTest.java
package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BSEFilingsAdapterTest {
    @Test void name_returnsBse() {
        assertThat(new BSEFilingsAdapter(null).name()).isEqualTo("bse-filings");
    }
    @Test void supports_corporateFilings() {
        BSEFilingsAdapter a = new BSEFilingsAdapter(null);
        assertThat(a.supports(DataCapability.CORPORATE_FILINGS)).isTrue();
        assertThat(a.supports(DataCapability.REAL_TIME_QUOTE)).isFalse();
    }
}
```

- [ ] **Step 3: Implement BSEFilingsAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BSEFilingsAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "bse-filings"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.CORPORATE_FILINGS; }
    @Override public boolean isHealthy() { return true; }

    /**
     * Fetches shareholding pattern for a BSE scrip code.
     * BSE API: https://www.bseindia.com/corporates/shpAPI.aspx?scripcode={code}&qtrid=1
     */
    public DataEnvelope<PromoterFilingSnapshot> fetchShareholdingPattern(String bseScripCode, String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.bseindia.com/corporates/shpAPI.aspx"
                    + "?scripcode=" + bseScripCode + "&qtrid=1")
                .header("User-Agent", "Mozilla/5.0")
                .retrieve()
                .body(Map.class);

            if (response == null) return DataEnvelope.missing(name(), "Empty response for " + bseScripCode);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("Table");
            if (data == null || data.isEmpty()) return DataEnvelope.missing(name(), "No data for " + bseScripCode);

            // Aggregate by category
            BigDecimal promoterPct = BigDecimal.ZERO, promoterPledgedPct = BigDecimal.ZERO;
            BigDecimal fiiPct = BigDecimal.ZERO, diiPct = BigDecimal.ZERO, retailPct = BigDecimal.ZERO;
            String quarterEnd = "";

            for (Map<String, Object> row : data) {
                String category = row.getOrDefault("Shareholder_Category", "").toString();
                BigDecimal pct = new BigDecimal(row.getOrDefault("Shareholding_Percentage", "0").toString());
                quarterEnd = row.getOrDefault("Quarter_Date", "").toString();
                if (category.contains("Promoter")) promoterPct = promoterPct.add(pct);
                else if (category.contains("FII") || category.contains("Foreign")) fiiPct = fiiPct.add(pct);
                else if (category.contains("DII") || category.contains("Mutual Fund")) diiPct = diiPct.add(pct);
                else if (category.contains("Public")) retailPct = retailPct.add(pct);
            }

            PromoterFilingSnapshot snap = new PromoterFilingSnapshot(
                symbol, quarterEnd, promoterPct, promoterPledgedPct, fiiPct, diiPct, retailPct);
            return DataEnvelope.of(snap, name(), DataQuality.EOD);

        } catch (Exception e) {
            log.error("[BSEFilings] fetch failed for {}: {}", bseScripCode, e.getMessage());
            return DataEnvelope.missing(name(), "BSE API error: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test -Dtest=BSEFilingsAdapterTest 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapter.java src/main/java/org/amit/finwise/marketdata/provider/PromoterFilingSnapshot.java src/test/java/org/amit/finwise/marketdata/provider/adapter/BSEFilingsAdapterTest.java
git commit -m "feat(data-fabric): BSEFilingsAdapter — promoter pledging + shareholding patterns"
```

---

### Task 5: NSEAnnouncementsAdapter + SEBIInsiderAdapter + WorldBankAdapter

Three small, free adapters sharing the same test file.

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/NSEAnnouncementsAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/SEBIInsiderAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/WorldBankAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/CorporateEventCalendar.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/InsiderTrade.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/adapter/SmallAdaptersTest.java`

- [ ] **Step 1: Create result records**

```java
// src/main/java/org/amit/finwise/marketdata/provider/CorporateEventCalendar.java
package org.amit.finwise.marketdata.provider;

import java.time.LocalDate;
import java.util.List;

public record CorporateEventCalendar(
    String symbol,
    List<CorporateEvent> upcomingEvents
) {
    public record CorporateEvent(String eventType, LocalDate eventDate, String description) {}
}
```

```java
// src/main/java/org/amit/finwise/marketdata/provider/InsiderTrade.java
package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InsiderTrade(
    String symbol,
    String personName,
    String designation,
    String tradeType,       // "BUY" | "SELL"
    BigDecimal quantity,
    BigDecimal price,
    LocalDate tradeDate
) {}
```

- [ ] **Step 2: Write tests**

```java
// src/test/java/org/amit/finwise/marketdata/provider/adapter/SmallAdaptersTest.java
package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SmallAdaptersTest {

    @Test void nseAnnouncements_name() {
        assertThat(new NSEAnnouncementsAdapter(null).name()).isEqualTo("nse-announcements");
    }
    @Test void nseAnnouncements_supports() {
        assertThat(new NSEAnnouncementsAdapter(null).supports(DataCapability.ANNOUNCEMENTS)).isTrue();
        assertThat(new NSEAnnouncementsAdapter(null).supports(DataCapability.MACRO_GLOBAL)).isFalse();
    }
    @Test void sebiInsider_name() {
        assertThat(new SEBIInsiderAdapter(null).name()).isEqualTo("sebi-insider");
    }
    @Test void worldBank_name() {
        assertThat(new WorldBankAdapter(null).name()).isEqualTo("world-bank");
    }
    @Test void worldBank_supports() {
        assertThat(new WorldBankAdapter(null).supports(DataCapability.WORLD_BANK)).isTrue();
    }
}
```

- [ ] **Step 3: Implement NSEAnnouncementsAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/NSEAnnouncementsAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NSEAnnouncementsAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "nse-announcements"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.ANNOUNCEMENTS; }
    @Override public boolean isHealthy() { return true; }

    public DataEnvelope<CorporateEventCalendar> fetchUpcomingEvents(String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.nseindia.com/api/event-calendar?symbol=" + symbol)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events = response != null
                ? (List<Map<String, Object>>) response.get("data") : List.of();
            if (events == null) events = List.of();

            List<CorporateEventCalendar.CorporateEvent> mapped = events.stream()
                .map(e -> new CorporateEventCalendar.CorporateEvent(
                    (String) e.getOrDefault("purpose", ""),
                    parseDate((String) e.getOrDefault("date", "")),
                    (String) e.getOrDefault("description", "")
                )).toList();

            return DataEnvelope.of(new CorporateEventCalendar(symbol, mapped), name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[NSEAnnouncements] fetch failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }

    private LocalDate parseDate(String raw) {
        try { return LocalDate.parse(raw.substring(0, 10)); }
        catch (Exception e) { return null; }
    }
}
```

- [ ] **Step 4: Implement SEBIInsiderAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/SEBIInsiderAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SEBIInsiderAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "sebi-insider"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.INSIDER_TRADES; }
    @Override public boolean isHealthy() { return true; }

    public DataEnvelope<List<InsiderTrade>> fetchInsiderTrades(String symbol, LocalDate since) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.nseindia.com/api/corporates-pit?symbol=" + symbol
                    + "&from=" + since + "&to=" + LocalDate.now())
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = response != null
                ? (List<Map<String, Object>>) response.get("data") : List.of();
            if (data == null) return DataEnvelope.of(List.of(), name(), DataQuality.EOD);

            List<InsiderTrade> trades = data.stream().map(t -> new InsiderTrade(
                symbol,
                (String) t.getOrDefault("acqName", ""),
                (String) t.getOrDefault("personCategory", ""),
                (String) t.getOrDefault("tdpTransactionType", ""),
                new BigDecimal(t.getOrDefault("noOfShareAcq", "0").toString()),
                new BigDecimal(t.getOrDefault("acqPriceTo", "0").toString()),
                parseDate((String) t.getOrDefault("date", ""))
            )).toList();

            return DataEnvelope.of(trades, name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[SEBIInsider] fetch failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }

    private LocalDate parseDate(String raw) {
        try { return LocalDate.parse(raw.substring(0, 10)); }
        catch (Exception e) { return LocalDate.now(); }
    }
}
```

- [ ] **Step 5: Implement WorldBankAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/WorldBankAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorldBankAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "world-bank"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.WORLD_BANK; }
    @Override public boolean isHealthy() { return true; }

    /** Fetches latest annual India GDP growth rate from World Bank API (free, no auth). */
    public DataEnvelope<BigDecimal> fetchIndiaGdpGrowth() {
        try {
            // World Bank API: https://api.worldbank.org/v2/country/IN/indicator/NY.GDP.MKTP.KD.ZG?format=json&mrv=1
            List<?> response = restClientBuilder.build()
                .get()
                .uri("https://api.worldbank.org/v2/country/IN/indicator/NY.GDP.MKTP.KD.ZG"
                    + "?format=json&mrv=1")
                .retrieve()
                .body(List.class);

            if (response == null || response.size() < 2) return DataEnvelope.missing(name(), "Empty response");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get(1);
            if (data == null || data.isEmpty()) return DataEnvelope.missing(name(), "No data");

            Object value = data.get(0).get("value");
            if (value == null) return DataEnvelope.missing(name(), "Null value");
            return DataEnvelope.of(new BigDecimal(value.toString()), name(), DataQuality.EOD);
        } catch (Exception e) {
            log.warn("[WorldBank] fetch failed: {}", e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=SmallAdaptersTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 5 tests green

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/adapter/ src/main/java/org/amit/finwise/marketdata/provider/CorporateEventCalendar.java src/main/java/org/amit/finwise/marketdata/provider/InsiderTrade.java src/test/java/org/amit/finwise/marketdata/provider/adapter/SmallAdaptersTest.java
git commit -m "feat(data-fabric): NSEAnnouncementsAdapter + SEBIInsiderAdapter + WorldBankAdapter"
```

---

### Task 6: NSEOptionChainAdapter + ZerodhaQuoteAdapter + ScreenerFundamentalsAdapter

Three high-value adapters that close the live data gap.

**Files:**
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/NSEOptionChainAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/ZerodhaQuoteAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/adapter/ScreenerFundamentalsAdapter.java`
- Create: `src/main/java/org/amit/finwise/marketdata/provider/LiveQuote.java`
- Test: `src/test/java/org/amit/finwise/marketdata/provider/adapter/LiveAdaptersTest.java`

- [ ] **Step 1: Create LiveQuote record**

```java
// src/main/java/org/amit/finwise/marketdata/provider/LiveQuote.java
package org.amit.finwise.marketdata.provider;

import java.math.BigDecimal;
import java.time.Instant;

public record LiveQuote(
    String symbol,
    BigDecimal lastPrice,
    BigDecimal change,
    BigDecimal changePct,
    BigDecimal volume,
    BigDecimal high52w,
    BigDecimal low52w,
    Instant timestamp
) {}
```

- [ ] **Step 2: Write tests**

```java
// src/test/java/org/amit/finwise/marketdata/provider/adapter/LiveAdaptersTest.java
package org.amit.finwise.marketdata.provider.adapter;

import org.amit.finwise.marketdata.provider.DataCapability;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LiveAdaptersTest {

    @Test void nseOptionChain_supports() {
        assertThat(new NSEOptionChainAdapter(null).supports(DataCapability.OPTION_CHAIN)).isTrue();
    }
    @Test void zerodhaQuote_supports() {
        ZerodhaQuoteAdapter a = new ZerodhaQuoteAdapter(null);
        assertThat(a.supports(DataCapability.REAL_TIME_QUOTE)).isTrue();
        assertThat(a.supports(DataCapability.HISTORICAL_OHLCV)).isTrue();
    }
    @Test void screener_supports() {
        assertThat(new ScreenerFundamentalsAdapter(null).supports(DataCapability.FUNDAMENTALS)).isTrue();
    }
}
```

- [ ] **Step 3: Implement NSEOptionChainAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/NSEOptionChainAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NSEOptionChainAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "nse-option-chain"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.OPTION_CHAIN; }
    @Override public boolean isHealthy() { return true; }

    /** Returns raw option chain map for a symbol from NSE's free endpoint. */
    public DataEnvelope<Map<?, ?>> fetchOptionChain(String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.nseindia.com/api/option-chain-equities?symbol=" + symbol)
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .header("Referer", "https://www.nseindia.com")
                .retrieve()
                .body(Map.class);
            return response != null
                ? DataEnvelope.of(response, name(), DataQuality.LIVE)
                : DataEnvelope.missing(name(), "Empty response for " + symbol);
        } catch (Exception e) {
            log.warn("[NSEOptionChain] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Implement ZerodhaQuoteAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/ZerodhaQuoteAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ZerodhaQuoteAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Value("${broker.zerodha.api-key:}")
    private String apiKey;

    @Override public String name() { return "zerodha-quote"; }

    @Override
    public boolean supports(DataCapability c) {
        return c == DataCapability.REAL_TIME_QUOTE || c == DataCapability.HISTORICAL_OHLCV;
    }

    @Override
    public boolean isHealthy() { return apiKey != null && !apiKey.isBlank(); }

    public DataEnvelope<LiveQuote> fetchQuote(String instrumentToken, String symbol, String accessToken) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://api.kite.trade/quote?i=NSE:" + symbol)
                .header("Authorization", "token " + apiKey + ":" + accessToken)
                .retrieve()
                .body(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) ((Map<?, ?>) response)
                .get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> q = (Map<String, Object>) data.get("NSE:" + symbol);
            if (q == null) return DataEnvelope.missing(name(), "Symbol not found: " + symbol);

            @SuppressWarnings("unchecked")
            Map<String, Object> ohlc = (Map<String, Object>) q.get("ohlc");

            LiveQuote quote = new LiveQuote(
                symbol,
                new BigDecimal(q.get("last_price").toString()),
                new BigDecimal(q.getOrDefault("net_change", "0").toString()),
                new BigDecimal(q.getOrDefault("change", "0").toString()),
                new BigDecimal(q.getOrDefault("volume", "0").toString()),
                new BigDecimal(q.getOrDefault("upper_circuit_limit", "0").toString()),
                new BigDecimal(q.getOrDefault("lower_circuit_limit", "0").toString()),
                Instant.now()
            );
            return DataEnvelope.of(quote, name(), DataQuality.LIVE);
        } catch (Exception e) {
            log.warn("[ZerodhaQuote] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Implement ScreenerFundamentalsAdapter**

```java
// src/main/java/org/amit/finwise/marketdata/provider/adapter/ScreenerFundamentalsAdapter.java
package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScreenerFundamentalsAdapter implements MarketDataProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "screener"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.FUNDAMENTALS; }
    @Override public boolean isHealthy() { return true; }

    /**
     * Fetches company fundamentals from Screener.in's JSON endpoint.
     * Returns raw map — callers extract what they need.
     * URL: https://www.screener.in/api/company/{symbol}/
     */
    public DataEnvelope<Map<?, ?>> fetchFundamentals(String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.screener.in/api/company/" + symbol + "/")
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json")
                .retrieve()
                .body(Map.class);
            return response != null
                ? DataEnvelope.of(response, name(), DataQuality.EOD)
                : DataEnvelope.missing(name(), "No data for " + symbol);
        } catch (Exception e) {
            log.warn("[Screener] failed for {}: {}", symbol, e.getMessage());
            return DataEnvelope.missing(name(), e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./mvnw test -Dtest=LiveAdaptersTest 2>&1 | tail -10
```
Expected: BUILD SUCCESS, 3 tests green

- [ ] **Step 7: Run full suite**

```bash
./mvnw test 2>&1 | tail -10
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/marketdata/provider/ src/test/java/org/amit/finwise/marketdata/provider/adapter/LiveAdaptersTest.java
git commit -m "feat(data-fabric): NSEOptionChainAdapter + ZerodhaQuoteAdapter + ScreenerFundamentalsAdapter"
```
