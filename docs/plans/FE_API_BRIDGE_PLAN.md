# FE API Bridge Plan

**Date:** 2026-06-24  
**Purpose:** Close the gap between the FE-required API contract and the current backend surface.

---

## Gap Analysis

The FE requires 13 endpoints. Below is the status of each.

| # | FE Required Endpoint | Status | Existing Backend |
|---|---|---|---|
| 1 | `GET /api/portfolio/performance/series?range=1M\|6M\|1Y\|ALL` | **MISSING** | `PortfolioPerformanceService` has TWRR but no time-series of portfolio values |
| 2 | `GET /api/market/tickers` | **MISSING** | `StockPriceService.closeOn()` can serve NIFTY/SENSEX/BANKNIFTY; no public controller |
| 3 | `GET /api/portfolio/risk/var` | **PATH MISMATCH** | `PortfolioRiskService.compute()` → `RiskDecomposition` has all VaR fields |
| 4 | `GET /api/portfolio/risk/backtest` | **PATH MISMATCH** | `VarBacktestService.backtest()` exists, returns `List<VarBacktestReport>` |
| 5 | `GET /api/portfolio/risk/contributors` | **PATH MISMATCH** | `RiskDecomposition.riskContributors` has per-holding data |
| 6 | `GET /api/portfolio/attribution?window=1Q\|6M\|1Y` | **PATH MISMATCH** | `GET /api/cfo/attribution` |
| 7 | `GET /api/goals/{id}/montecarlo?sip=18500` | **PATH MISMATCH** | `GET /api/finance/goal/{id}/simulate?sip=...` |
| 8 | `GET /api/brief/daily` | **PATH MISMATCH** | `GET /api/cfo/brief` |
| 9 | `POST /api/brief/refresh` | **MISSING** | No brief regeneration endpoint exists |
| 10 | `POST /api/insights/{id}/dismiss` | **MISSING** | Insights exist at `GET /api/cfo/insights`; no dismiss |
| 11 | `POST /api/chat/message` | **PATH MISMATCH** | `POST /api/cfo/chat` |
| 12 | `GET/PUT /api/users/me/profile` | **PATH MISMATCH** | `GET/PUT /api/cfo/profile` |
| 13 | `GET /api/portfolio/look-through` | **PATH MISMATCH** | `GET /api/cfo/look-through` |

**Summary:** 3 truly missing (need new service logic), 10 path-mismatch (thin delegate controllers).

---

## Implementation Phases

### Phase 1 — `PortfolioController` (new file)
**Path:** `src/main/java/org/amit/finwise/portfolio/controller/PortfolioController.java`  
**Base mapping:** `/api/portfolio`

#### 1A — `GET /api/portfolio/performance/series`

**New service required:** `PortfolioValueSeriesService`  
**Location:** `cfo/service/analytics/PortfolioValueSeriesService.java`

**Logic:**
- For each trading day in the requested range, reconstruct the portfolio value by multiplying each holding's quantity by its closing price on that date using `BackbonePriceReader` / `StockPriceHistory`.
- Use the holding's `purchaseDate` to know when it entered the portfolio (holdings purchased after a date contribute 0 before that date).
- Ranges map to lookback windows: `1M` = 30 days, `6M` = 180 days, `1Y` = 365 days, `ALL` = since oldest holding purchase date.
- Return `{ points: [{ date: "YYYY-MM-DD", value: 1234567.89 }] }` — one point per trading day.

**Response DTO:** `record PortfolioValuePoint(LocalDate date, BigDecimal value) {}`  
**Wrapper:** `record PortfolioValueSeriesResponse(List<PortfolioValuePoint> points) {}`

#### 1B — `GET /api/portfolio/risk/var`

Delegate to `PortfolioRiskService.compute(userId)`. Project only VaR/CVaR fields from `RiskDecomposition`.

**Response DTO:**
```java
record VarSummaryResponse(List<VarMethodEntry> methods) {}
record VarMethodEntry(String label, double value, String confidence) {}
```

Map:
- `var95Parametric` → label="Parametric", confidence="95%"
- `var95CornishFisher` → label="Cornish-Fisher", confidence="95%"
- `var95Historical` → label="Historical", confidence="95%"
- `cvar95` → label="CVaR", confidence="95%"

Return 503 if `compute()` returns empty (insufficient data).

#### 1C — `GET /api/portfolio/risk/backtest`

Delegate to `VarBacktestService.backtest(userId)`. Return the list of `VarBacktestReport` directly (already serializable).

#### 1D — `GET /api/portfolio/risk/contributors`

Delegate to `PortfolioRiskService.compute(userId)`. Return `riskContributors` list from `RiskDecomposition`.

**Response DTO:** `record ContributorsResponse(List<RiskDecomposition.RiskContributor> contributors) {}`

#### 1E — `GET /api/portfolio/attribution?window=1Q|6M|1Y`

Delegate to `AttributionService` (same service called by `GET /api/cfo/attribution`). Read the window param and pass it through identically.

#### 1F — `GET /api/portfolio/look-through`

Delegate to `LookThroughService` (same service called by `GET /api/cfo/look-through`).

---

### Phase 2 — `MarketController` (new file)
**Path:** `src/main/java/org/amit/finwise/marketdata/controller/MarketController.java`  
**Base mapping:** `/api/market`

#### `GET /api/market/tickers`

**Logic:**
- Query `StockPriceHistory` for the latest row for each of: `^NSEI` (NIFTY), `^BSESN` (SENSEX), `^NSEBANK` (BANKNIFTY).
- Use `StockPriceService.closeOn(symbol, LocalDate.now())` or a direct repository query for the most recent row.
- Map symbol → friendly label: `^NSEI` → "NIFTY 50", `^BSESN` → "SENSEX", `^NSEBANK` → "BANKNIFTY".

**Response DTO:**
```java
record MarketTickerResponse(List<TickerEntry> tickers) {}
record TickerEntry(String symbol, BigDecimal value, Double changePct) {}
```

`changePct` comes from `StockPriceHistory.priceChangePercent` on the latest row.  
If a symbol has no data yet, omit it from the list (don't error).

---

### Phase 3 — `BriefController` (new file)
**Path:** `src/main/java/org/amit/finwise/cfo/controller/BriefController.java`  
**Base mapping:** `/api/brief`

#### `GET /api/brief/daily`

Delegate to the same service/repository that `GET /api/cfo/brief` uses. Return identical response shape.

Check `CFOController.getBrief()` at line 60 and replicate the call here.

#### `POST /api/brief/refresh`

Call `CFOAdvisorService.generateDailyBrief(userId)` (or the async wrapper used by the scheduler).  
Return `{ "status": "refresh triggered" }` immediately (async — don't block).

---

### Phase 4 — Insight Dismiss
**Files:** `InsightCard.java`, `CFOController.java` (or new `InsightController.java`)

#### `POST /api/insights/{id}/dismiss`

**Step 1 — check InsightCard model:**  
If `InsightCard` does not have a `dismissed` / `dismissedAt` field, add:
```java
@Column(name = "dismissed_at")
private java.time.Instant dismissedAt;
```

**Step 2 — add repository method:**  
In `InsightCardRepository`, add a findById-style method returning `Optional<InsightCard>`.

**Step 3 — add endpoint:**  
Add to `CFOController` (or a new `InsightController` at `/api/insights`):
```
POST /api/insights/{id}/dismiss
```
Load the card by id, verify it belongs to the authenticated user, set `dismissedAt = Instant.now()`, save. Return 200 with the updated card or a simple `{ "dismissed": true }`.

**Step 4 — filter dismissed cards from `GET /api/cfo/insight-cards`:**  
Update the query/service to exclude cards where `dismissedAt IS NOT NULL`.

---

### Phase 5 — `GoalsController` (new file)
**Path:** `src/main/java/org/amit/finwise/goal/controller/GoalsController.java`  
**Base mapping:** `/api/goals`

#### `GET /api/goals/{id}/montecarlo?sip=18500`

Delegate to `MonteCarloGoalService.simulate(goal, sip)` — same logic as `GET /api/finance/goal/{id}/simulate`. Load goal by id, verify ownership, call service, return result.

---

### Phase 6 — `UserController` (new file)
**Path:** `src/main/java/org/amit/finwise/auth/UserController.java`  
**Base mapping:** `/api/users`

#### `GET /api/users/me/profile`

Delegate to same service called by `GET /api/cfo/profile` (line 379 in CFOController).

#### `PUT /api/users/me/profile`

Delegate to same service called by `PUT /api/cfo/profile` (line 387 in CFOController).

---

### Phase 7 — `ChatController` (new file)
**Path:** `src/main/java/org/amit/finwise/cfo/controller/ChatController.java`  
**Base mapping:** `/api/chat`

#### `POST /api/chat/message { message: string }`

Delegate to the same handler as `POST /api/cfo/chat` (line 264 in CFOController). Accept `{ "message": "..." }` body, forward to `CFOAdvisorService.chat()` or equivalent, return the same response shape.

---

## Build Order

| Order | Phase | New Files | Effort |
|---|---|---|---|
| 1 | Phase 4 — Dismiss | `InsightCard.java` (field), `CFOController.java` (endpoint) | Low |
| 2 | Phase 3 — BriefController | `BriefController.java` | Low |
| 3 | Phase 7 — ChatController | `ChatController.java` | Low |
| 4 | Phase 6 — UserController | `UserController.java` | Low |
| 5 | Phase 5 — GoalsController | `GoalsController.java` | Low |
| 6 | Phase 1B/C/D/E/F — PortfolioController (risk+attribution+look-through) | `PortfolioController.java` | Medium |
| 7 | Phase 2 — MarketController | `MarketController.java` | Medium |
| 8 | Phase 1A — Portfolio value series | `PortfolioValueSeriesService.java` + controller endpoint | High |

---

## Notes

- All new controllers should use `@AuthenticationPrincipal` / `Principal` for `userId` — follow the pattern in `CFOController`.
- No new JPA entities are needed except the `dismissed_at` column on `InsightCard` (Hibernate `ddl-auto=update` will add it automatically).
- The `PortfolioValueSeriesService` (Phase 1A) is the most complex piece — it needs to walk `StockPriceHistory` across the date range for all holdings simultaneously. Cache the result in a `@Cacheable` or store as a `PortfolioSnapshot` (the model already exists) to avoid recomputing on every request.
- The existing `/api/cfo/**` endpoints should **not** be removed — the admin dashboard and existing FE code may rely on them.
