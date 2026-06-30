# Finwise — World-Class Honest Financial Advisory System
**Design Spec | 2026-06-30**

## Vision

Finwise will be the world's most competitive, honest, and powerful financial advisory system for Indian investors — starting with HNIs, scaling to retail, then offered as a B2B engine to SEBI-registered RIAs.

The moat is the intersection of three properties no Indian platform currently holds simultaneously:
1. **Quantitative honesty** — every claim backed by audited numbers, confidence intervals surfaced, graceful degradation declared
2. **Fiduciary alignment** — conflict of interest disclosed per recommendation; fee-only advisory today, transaction commissions disclosed transparently when introduced
3. **Brutal honesty** — portfolio scored without softening; benchmark drag, false diversification, goal funding gaps stated plainly

---

## Target Users (sequential)

| Phase | User | Acquisition thesis |
|-------|------|-------------------|
| B (now) | HNI / serious DIY investor — ₹50L+ portfolio, multi-broker | Prove the engine; depth over breadth |
| A (6–12 months) | Self-directed retail — ₹1L–₹50L, Zerodha/Groww | Scale; word-of-mouth from HNI phase |
| C (post-funding) | SEBI-registered RIAs | B2B SaaS; white-label engine for 50–500 client practices |

---

## Architecture Overview

### Current state
```
[Single user "amit"] → [Spring Boot monolith]
    ├── CFO engine (institutional-grade quant ✅ 281 tests)
    ├── Insight cards (calibrated, honesty-validated ✅)
    ├── Simulation / backtest ✅
    ├── Policy intelligence (lexical FTS only)
    ├── Company view (plan exists, not built)
    ├── Data sources (fragile free scraping — highest risk)
    └── No auth, no multi-tenancy ← blocks all growth
```

### Target architecture
```
[Users: HNI → Retail → RIA]
        │
        ▼
[JWT Auth + Multi-tenancy]                        ← Track 2
        │
        ▼
[API Gateway — Spring controllers]
        │
   ┌────┴──────────────────────────────────────────┐
   │                                               │
   ▼                                               ▼
[Data Fabric]                          [Analytics + Advisory Engine]
  MarketDataProvider interface            (existing — never touched)
  ├── NSEBhavcopAdapter (existing)        ├── GARCH / Ledoit-Wolf / Brinson-Fachler
  ├── YahooAdapter (existing)             ├── Factor model / Monte Carlo
  ├── AMFIAdapter (existing)              ├── Insight cards (11 generators)
  ├── ZerodhaKiteAdapter (new)            ├── VaR backtest / stress scenarios
  ├── DhanAdapter (new)                   ├── Tax harvesting
  ├── UpstoxAdapter (new)                 ├── Company view (to build)
  ├── ScreenerAdapter (new)               └── Policy RAG (to build)
  ├── FREDAdapter (new)                             │
  ├── BSEFilingsAdapter (new)                       ▼
  ├── NSEAnnouncementsAdapter (new)      [Fiduciary Layer]              ← Track 3
  ├── SEBIInsiderAdapter (new)             FiduciaryWrapper (per response)
  ├── NSEOptionChainAdapter (new)          HardTruthEngine
  └── [RefinitivAdapter — post-funding]    AuditTrailService
                                           ConflictDisclosureConfig
```

### Key architectural principle
`MarketDataProvider` mirrors the existing `LLMProvider` strategy pattern exactly. The analytics engine reads from the data fabric and never knows whether quotes come from Yahoo, Zerodha, or Refinitiv. Post-funding, swap in institutional adapters with zero analytics changes.

The monolith stays. Package boundaries are the right isolation unit pre-PMF.

---

## Track 1 — Data Fabric

### Interface

```java
public interface MarketDataProvider {
    String name();                        // "zerodha-kite", "screener", "fred"
    boolean supports(DataCapability cap); // REAL_TIME_QUOTE, FUNDAMENTALS, MACRO, etc.
    boolean isHealthy();                  // Resilience4j circuit breaker state
}
```

`MarketDataRouter` selects the best healthy adapter per `DataCapability`. Resilience4j `CircuitBreaker` per adapter — trips after 3 failures, reopens after 60s. On trip, router falls back to next capable adapter automatically.

### Data envelope
Every data point carries provenance:

```java
record DataEnvelope<T>(
    T value,
    String source,         // "zerodha-kite"
    Instant fetchedAt,
    DataQuality quality,   // LIVE | EOD | ESTIMATED | STALE | MISSING
    String fallbackNote    // "live unavailable; using EOD close (staleness: 4h)"
)
```

`FiduciaryWrapper` surfaces `quality` and `fallbackNote` in every recommendation. `STALE` or `MISSING` data is declared explicitly — never silently used.

### Data source inventory

#### Tier 1 — Free, add immediately

| Source | Adapter | Data provided | Why it matters |
|--------|---------|--------------|----------------|
| FRED API | `FREDMacroAdapter` | Fed Funds rate, DXY, crude WTI/Brent, gold spot, VIX, US 10Y, global CPI | Global macro overlay; DXY impact on FII flows, crude on oil sector |
| BSE XBRL filings | `BSEFilingsAdapter` | Promoter pledge %, shareholding patterns (quarterly) | Best early-warning for governance blow-ups (IL&FS, DHFL, Zee patterns) |
| NSE announcements | `NSEAnnouncementsAdapter` | Board meeting dates, results calendar, AGM, ex-dividend dates | Forward event calendar for HNI positioning |
| NSE option chain | `NSEOptionChainAdapter` | Live IV per strike, OI, PCR, ATM term structure | Feeds existing Black-Scholes / IV solver (BPR-4) with live data |
| SEBI insider disclosures | `SEBIInsiderAdapter` | Director/promoter buy-sell filings | Strong signal: promoter buying in open market vs pledging |
| World Bank API | `WorldBankAdapter` | India GDP, CPI, current account, FDI (annual) | Long-horizon macro for goal engine |
| RBI DBIE | `RBIDatabaseAdapter` | Repo rate history, CRR, SLR, M3, sectoral credit growth | Structured replacement for FBIL scraping |

#### Tier 2 — Broker APIs, add immediately (₹0–₹2K/mo)

| Broker | Cost | Capabilities | Strategic value |
|--------|------|-------------|-----------------|
| Zerodha Kite Connect | ₹2K/mo | Real-time quotes, historical OHLCV, option chain, portfolio sync | Real-time data + multi-broker onboarding entry point |
| Dhan API | Free | Real-time quotes, portfolio sync, option chain | Zero-cost fallback to Kite |
| Upstox API | Free | 5-year historical OHLCV, portfolio sync | Best free historical data; fills 3Y seed gaps |
| Angel One SmartAPI | Free | Real-time quotes, option chain | Covers Angel's 25M+ user base |

**Multi-broker sync** is the moat against Smallcase and brokers. Smallcase lives inside one broker. Finwise sees the whole picture: Zerodha stocks + HDFC MF + Groww SIPs + LIC — no Indian platform does this.

#### Tier 3 — Freemium (add within 30 days)

| Source | Cost | Data |
|--------|------|------|
| Screener.in | Free scrape / ₹999 API | 10-year quarterly financials, ratios, 5000+ stocks — far richer than Yahoo |
| Tickertape | Free | Analyst estimates, consensus EPS, price targets |
| Trendlyne | Free tier | Corporate governance score, promoter activity alerts |

#### Tier 4 — Post-funding institutional (₹5L–₹50L/year)

| Source | Replaces | Value |
|--------|---------|-------|
| NSE data products | NSE scraping | Official, SLA-backed, co-location eligible |
| CMIE PROWESS | Yahoo fundamentals | 25+ years, 35,000 companies, quarterly balance sheets |
| CRISIL ratings API | Gap today | Credit quality on bonds and NCD holdings |
| Refinitiv Eikon | Everything else | Global events, earnings revisions, tick data |
| Alternative data (GST, satellite, credit card) | — | True alpha edge; AlphaWave, SimilarWeb |

### Data capability gap closure

| Capability | Today | After Tier 1+2 | Post-funding |
|-----------|-------|----------------|-------------|
| Real-time quotes | ❌ | ✅ | ✅ |
| Multi-broker portfolio sync | Groww only | ✅ 4+ brokers | ✅ All |
| Promoter pledging | ❌ | ✅ BSE XBRL | ✅ |
| Global macro (DXY, crude, VIX) | ❌ | ✅ FRED | ✅ Bloomberg |
| Option chain live IV | Math built, no feed | ✅ NSE/Dhan | ✅ |
| Insider trading disclosures | ❌ | ✅ SEBI filings | ✅ |
| Deep fundamentals (10yr) | Yahoo (fragile) | ✅ Screener | ✅ CMIE |
| Forward events calendar | ❌ | ✅ NSE announcements | ✅ |
| Credit/bond ratings | ❌ | ❌ | ✅ CRISIL |

---

## Track 2 — Productionization & Multi-broker OAuth

### Auth & multi-tenancy
Follows existing `Productionisation_plan.md` exactly — JWT (jjwt 0.12.x), BCrypt, `SecurityConfig`, `JwtAuthenticationFilter`, `AuthController`, `CurrentUserProvider`.

Addition: `UserProfile` entity (separate from `User`) stores financial context — risk tolerance, investment horizon, home city, tax bracket. Feeds CFO engine personalization. Collected during onboarding.

```
POST /api/auth/register  → User entity (username = userId, BCrypt)
POST /api/auth/login     → JWT (HS256, subject = username, TTL 7d)
GET  /api/auth/me        → UserProfile
```

All controllers derive `userId` from principal, never from request param. Closes the cross-tenant read hole across all 6 affected controllers.

### Multi-broker OAuth

```java
// BrokerConnection entity
String userId
BrokerEnum broker          // ZERODHA | UPSTOX | DHAN | ANGEL | GROWW | FYERS
String accessToken         // AES-encrypted (same key as GrowwConnector today)
String refreshToken        // AES-encrypted
Instant tokenExpiresAt
ConnectionStatus status    // ACTIVE | EXPIRED | REVOKED
Instant lastSyncedAt

// BrokerConnector interface
List<HoldingDTO> syncHoldings(userId, token)
List<TransactionDTO> syncTransactions(userId, token, since)
BrokerConnection refreshToken(connection)
```

`GrowwConnector` becomes the first implementation — already works, conforms to interface. `ZerodhaConnector`, `DhanConnector`, `UpstoxConnector` follow.

`HoldingDeduplicationService` merges positions by ISIN across brokers: sums quantities, computes blended average cost, attributes each lot to source broker. This is the "whole picture" that no Indian platform provides.

### Onboarding flow (HNI-first)
```
Step 1 — Register (email + password)
Step 2 — Risk profile (5 questions → CONSERVATIVE / MODERATE / AGGRESSIVE)
Step 3 — Connect brokers (OAuth per broker; skippable)
Step 4 — Import MF portfolio (CAMS/KFintech PDF → existing DocumentParserService)
Step 5 — Set goals (existing GoalController, pre-populated from portfolio)
Step 6 — First brief generated (CFOAdvisorService.generateDailyBrief)
```

Graceful degradation at every step: engine operates on partial data and declares what's missing.

### Scheduler fan-out
```java
// CFOScheduler — 7:30 AM IST
userRepository.findAllByEnabledTrue()
    .forEach(user -> executor.submit(
        () -> cfoAdvisorService.generateDailyBrief(user.getUsername())
    ));
```

Market-wide tasks (bhavcopy, policy crawl, macro refresh, option chain fetch) remain global — run once, shared. Per-user work: brief generation, insight cards, goal simulation only.

### Security boundaries

| Endpoint class | Auth requirement |
|---------------|-----------------|
| `/api/auth/**` | None |
| `/api/cfo/**`, `/api/investment/**`, etc. | `ROLE_USER` + own data only |
| `/api/admin/**`, `/api/market-data/admin/**` | `ROLE_ADMIN` |
| `/api/company/{symbol}` | `ROLE_USER` (global data, no userId filter) |
| `/api/simulation/**` | `ROLE_USER` + own portfolio |

---

## Track 3 — Honesty & Compliance Layer

### What already exists (do not rebuild)
- `ConfidenceCalibrationService` — shrinks confidence toward cohort hit rate
- `BriefHonestyValidator` — strips LLM sentences whose numbers don't match Java output
- `InsightNarrationService` — honesty validator integrated
- `VarBacktestService` — flags when VaR model is lying
- `InsightEvaluationService` — tracks recommendation accuracy over time

### Component 1 — FiduciaryWrapper

Wraps every API response containing advice or a recommendation:

```java
record FiduciaryEnvelope<T>(
    T data,
    String conflictStatement,   // dynamic — see ConflictDisclosureConfig
    List<String> dataSources,   // ["NSE-bhavcopy 2026-06-29", "Screener.in 2026-06-28"]
    String dataQualityNote,     // "Live quotes unavailable; EOD close used (staleness: 4h)"
    String confidenceSummary,   // "Cohort hit rate 58% @5d horizon, n=142"
    Instant generatedAt,
    String engineVersion
)
```

`ConflictDisclosureConfig` is dynamic, not hardcoded. It evolves with the business model:

- **Phase 1 (pure advisory):** `"Conflict: NONE. Finwise earns a flat subscription fee only."`
- **Phase 2 (transaction commissions):** `"Conflict: Finwise earns a referral fee of X bps on transactions executed via [Broker]. This recommendation is made independent of that fee."`
- **Phase 3 (RIA + distribution):** Full SEBI IA Regulations 2020 per-recommendation disclosure

Dynamic disclosure of actual conflicts is more honest than a permanent "zero commission" claim that may become untrue. SEBI mandates this disclosure; surfacing it per-recommendation is the differentiator.

### Component 2 — HardTruthEngine

Runs inside `InsightCardService.generate()`. Produces `PortfolioReportCard` cards that are never softened:

| Card | Signal | Example output |
|------|--------|---------------|
| `BenchmarkDragCard` | XIRR vs Nifty 50 / Nifty 500 / category avg | "You earned 9.1% XIRR. Nifty 500 returned 14.8%. Your picks cost you ₹1.84L over 3 years." |
| `FalseConcentrationCard` | Pairwise ρ among "diversified" holdings | "You hold 5 IT stocks with avg ρ=0.91. You own the same bet 5 times." |
| `BenchmarkHuggerCard` | Active share vs benchmark | "Active share: 23%. You pay active fund fees for index-level exposure." |
| `DormantHoldingCard` | Positions held >18mo, return < repo rate | "Ashok Leyland returned 1.2% in 22 months. FD equivalent: 14.3%. No thesis detected." |
| `PromotorRiskCard` | Pledge %, insider selling trend | "Promoter pledge crossed 72% — above 60% alert threshold." |
| `TaxDragCard` | Annual STCG from churn vs LTCG equivalent | "Your trading pattern generates ₹34,000/year in avoidable STCG tax." |
| `GoalFundingGapCard` | Monthly SIP gap vs Monte Carlo 75th-pctile need | "Goal 'House 2029' needs ₹18,400/mo at 75% confidence. You contribute ₹12,000. Gap: ₹6,400." |
| `OverfeeCard` | Active MF TER vs cheapest passive equivalent | "Active TER 1.82% vs passive 0.12%. Annual drag: ₹41,000." |

Severity follows existing enum: `ALERT / WATCH / INFO`. Real problems get `ALERT` or `WATCH` — never softened to `INFO`.

### Component 3 — AuditTrailService

Every recommendation written to `recommendation_audit` before serving. Immutable after insert.

```
recommendation_audit
  id              UUID PK
  userId          String
  type            REBALANCE | BUY | SELL | HOLD | GOAL_ADJUST | STRESS_FLAG
  symbol          String (nullable)
  rationale       text — Java-rendered reasoning, not LLM output
  confidence      double — calibrated at generation time
  conflictState   String — snapshot of ConflictDisclosureConfig at time of recommendation
  dataSnapshot    JSON — DataEnvelope values used
  engineVersion   String
  generatedAt     Instant (immutable)
  userAcked       boolean
  outcome         nullable — filled post-hoc by EventOutcomeService
  outcomeAt       Instant (nullable)
```

`EventOutcomeService` fills `outcome` when prediction window closes. `ConfidenceCalibrationService` reads from this table. `GET /api/cfo/audit?from=YYYY-MM-DD` exposes the full recommendation history + outcomes to the user — every call made, whether it was right. No Indian platform exposes this.

### Component 4 — SEBI Compliance Layer (Phase 3 / RIA — design now, build later)

- `ClientSuitabilityService` — blocks `AGGRESSIVE` recommendations for `CONSERVATIVE` profiles
- `RecommendationRationaleDocument` — SEBI IA Regulations 2020 written rationale, generated from audit record
- `AnnualReviewReminder` — scheduled yearly re-profiling prompt for RIA clients
- `FeeDisclosureStatement` — generated PDF: flat fee + per-transaction commission schedule

Built on top of the audit trail. No new data model needed at Phase 3.

---

## Remaining Analytics Gaps

### P9 — GARCH + Liquidity VaR
`GarchService` (GJR-GARCH) and `LiquidityService` exist. First step: verify wired and tested:
```bash
./mvnw test -Dtest=GarchServiceTest,LiquidityServiceTest
```
If not wired: `LiquidityVaR = HistoricalVaR × sqrt(holdingPeriod)` using existing ADV data. One `appendGarchLiquidityVaR` call in `CFOAdvisorService` closes it. Zerodha/Dhan real-time spread feeds into Corwin-Schultz estimator in `TradingCostService` for live LVaR.

### P10 — Options live feed
Math is built (Black-Scholes, Greeks, IV solver — BPR-4). Missing: live market data. `NSEOptionChainAdapter` (Tier 1 free data) closes the gap. New insight card: IV term structure inversion → near-term uncertainty flag. Feeds users who hold derivatives or want to hedge.

### Company Intelligence View
Plan at `COMPANY_VIEW_AND_POLICY_RAG_ROADMAP.md` is complete and ready to execute. Sequencing unlocked by data fabric:

| Priority | Cards | Data unlock |
|----------|-------|-------------|
| Highest | Corporate actions + forward calendar | `BSEFilingsAdapter` |
| High | Ownership + smart money (pledge, insider) | `SEBIInsiderAdapter` |
| High | Quote + relative strength vs sector | `ZerodhaKiteAdapter` |
| Medium | Event-study CAR around results | Existing `ReturnSeriesService` |
| Medium | Deep fundamentals | `ScreenerAdapter` |

### Policy RAG — Hybrid Retrieval
```
1. Add pgvector embedding column to PolicyChunk → reuse EmbeddingService
2. PolicyHybridRetriever — RRF fusion (lexical FTS + vector cosine)
3. Stock→policy bridge via SymbolExtractorService → Company View Card 6
4. LLM impact extraction via LlmRefinementService → replace 600-line inferImpacts()
```

### Explicitly deferred
Crypto, real estate valuation, insurance gap analysis, credit score monitoring, global portfolio (LRS/FEMA complexity) — all deferred to post-PMF.

---

## Competitive Position After Execution

| Competitor | Their moat | Finwise advantage |
|-----------|-----------|-------------------|
| Zerodha / Groww | Distribution, user base | Depth of analysis; fiduciary vs distributor |
| Smallcase | Curated baskets, broker embedding | Full portfolio view across all brokers |
| INDwealth / Centricity | HNI relationships, RM | Cost; transparent AUM-fee-free model |
| Jarvis / Tavaga | Robo-advisory UI | Institutional quant engine; honest confidence |
| International entrants | Brand, global scale | India-specific depth: tax engine, SEBI/RBI, regime model |

**The combined moat:** institutional quant engine (281 tests) + multi-broker whole-picture + fiduciary disclosure + brutal honesty scoring + calibrated confidence with public track record. None of the above competitors have more than two of these.

---

## Build Sequence (dependency order)

| # | Item | Track | Effort | Dependency |
|---|------|-------|--------|-----------|
| 1 | JWT auth + multi-tenancy | 2 | M | None — critical path |
| 2 | MarketDataProvider interface + router | 1 | S | None |
| 3 | ZerodhaKiteAdapter + DhanAdapter | 1 | S | #2 |
| 4 | BSEFilingsAdapter + SEBIInsiderAdapter + NSEAnnouncementsAdapter | 1 | S | #2 |
| 5 | FREDMacroAdapter + RBIDatabaseAdapter | 1 | S | #2 |
| 6 | ScreenerAdapter | 1 | S | #2 |
| 7 | NSEOptionChainAdapter | 1 | S | #2 |
| 8 | Multi-broker OAuth (Zerodha, Dhan, Upstox, Angel) | 2 | M | #1, #3 |
| 9 | HoldingDeduplicationService | 2 | S | #8 |
| 10 | UserProfile + onboarding flow | 2 | S | #1 |
| 11 | Scheduler fan-out | 2 | S | #1 |
| 12 | ConflictDisclosureConfig + FiduciaryWrapper | 3 | S | #1 |
| 13 | HardTruthEngine (8 cards) | 3 | M | #4, #5 |
| 14 | AuditTrailService | 3 | S | #12 |
| 15 | P9 GARCH/LVaR verify + wire | — | S | #7 |
| 16 | P10 Options live (NSE chain feed) | — | S | #7 |
| 17 | Company Intelligence View Phase 1 | — | M | #4, #3 |
| 18 | Policy RAG hybrid retrieval | — | M | None |
| 19 | SEBI Compliance Layer (RIA phase) | 3 | M | #14 |
