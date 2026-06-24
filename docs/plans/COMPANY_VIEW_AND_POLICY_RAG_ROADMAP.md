# Company Intelligence View + Policy RAG Roadmap

**Status:** Plan (approved 2026-06-24)
**Author:** Design analysis pass over existing codebase
**Goal:** (1) A Bloomberg-grade single-company view ("search TCS, see everything a beginner needs to decide"), and (2) upgrade the policy engine from a lexical retrieval + rule-based tagger into a reactive, hybrid RAG that tracks policy evolution over time.

---

## 0. Baseline — what already exists

The headline finding: **~80% of the data and math is already built.** The gap is wiring and surfacing, not new engines.

### Single-stock intelligence (`StockIntelligenceService.analyze(symbol, userId)` → `StockDeepDive`)
Already assembles: live quote + day change + circuit flags; technicals (RSI, MA, momentum); fundamentals (P/E, P/B, EV/EBITDA, EV/Sales, PEG, margins, ROE, D/E, FCF, dividend yield) with valuation z-scores (CHEAP/FAIR/EXPENSIVE) + modified Piotroski F-score + peer percentiles; quarterly trend (revenue/EPS, Sloan accruals, dilution); risk (max drawdown, downside beta, EWMA vol, stressed correlation, ADV/liquidity, GARCH, factor model); portfolio fit (marginal vol, correlation-to-portfolio, BUY/AVOID/DIVERSIFY verdict); macro snapshot; 7-day symbol-matched news; composite scorecard (score + recommendation + confidence + expected-return band).

### Market data persisted but NOT surfaced in the stock view (`marketdata/`)
`CorporateAction`, `CorporateEvent`, `Announcement`, `ShareholdingPattern`, `MarketDeal` (bulk/block deals), `IndexEod`, `PriceAdjustment`, `Instrument`, `MfNav` — with NSE/AMFI clients, bhavcopy parsers, gap-repair and data-quality ops.

### Policy engine (`policy/`)
- Versioned documents with content-hash, `supersedesReference`, `current` flag (`PolicyDocumentVersion`).
- Chunking (~1800 char / 200 overlap, `PolicyChunkingService`).
- Rich impact schema (`PolicyImpact`): subject type (sector/asset-class/factor/tax-topic/market-structure/theme), transmission channel, direction, horizon, surprise classification, legal-force rank, market-moving power, falsification signal.
- Retrieval is **pure Postgres lexical FTS** (`PolicySearchIndexService`, `tsvector`/GIN) — no vector embeddings.
- Impact extraction is **hardcoded keyword rules** (`PolicyDocumentCrawlerService.inferImpacts()`, ~600 lines of `haystack.contains(...)`).
- Crawler pulls RBI/SEBI/PIB via RSS→HTML→PDF on a fixed schedule (`PolicyIntelligenceScheduler`).

### Reusable infra to lean on
- `EmbeddingService` (already used for news relevance) — reuse for policy chunk embeddings.
- `LlmRefinementService` + `LLMProvider` strategy — reuse for LLM impact extraction.
- `EventOutcomeService` + `ConfidenceCalibrationService` (cfo/rag, cfo/insight) — template for the policy falsification/calibration loop.
- `InsightNarrationService` — template for the beginner narration layer.
- `notification` module — for reactive push.
- `SymbolExtractorService` + symbol gazetteer — for stock→sector→policy bridging.

---

## Phase 1 — Company Intelligence View (pure wiring, highest value)

**Outcome:** `GET /api/company/{symbol}` returns a 6-card beginner-facing profile. Mostly joins + DTO assembly over existing services/repos.

New: `CompanyProfileService.getProfile(symbol, userId)` aggregator + `CompanyProfileController`.

### Card 1 — Quote & Index Context
- Last price, day/52w change, 52w high-low band + percentile rank of current price in range, volume vs 20-day avg (volume z-score).
- **Relative strength:** `r_stock − r_NIFTY` and `r_stock − r_sectorIndex` over 1M/3M/1Y. Source: `IndexEod` + `ReturnSeriesService` (wire-up).

### Card 2 — Corporate Actions & Events (biggest quick win)
- Past actions (dividends w/ yield-on-cost, splits, bonuses, buybacks) from `CorporateActionRepository`.
- **Forward calendar** (ex-dividend, board meeting, results date, AGM) from `CorporateEventRepository` — countdown to each.
- Dividend-adjusted total return via existing `PriceAdjustment`.

### Card 3 — Ownership & Smart-Money
- Shareholding trend (promoter / FII / DII / retail %, **promoter pledge %**) over last 4 quarters from `ShareholdingPatternRepository`; QoQ deltas.
- Recent bulk/block deals from `MarketDealRepository`.
- Ownership-momentum score = `sign(ΔFII + ΔDII)`.

### Card 4 — Fundamentals & Valuation
- All metrics already computed in `StockDeepDive.fundamentals`. Add peer-relative verdicts ("P/E 28 — pricier than 70% of IT peers") via existing peer percentiles.

### Card 5 — Risk & Portfolio Fit
- Surface existing `StockDeepDive.portfolioFit` + `riskMetrics`: "Buying TCS adds X% portfolio vol, ρ=Y, verdict=DIVERSIFY."

### Card 6 — News & Policy Catalysts
- Stock news (exists) + stock-level policy exposure (delivered by Phase 2.3).

### Phase 1 math to add
1. **Event-study engine** (small, new): average abnormal return (AAR) and cumulative AAR in ±N-day window around past results/corporate events. `CAR = Σ(r_stock − r_expected)`. → "TCS historically drifts +1.8% in 3 days post-results."
2. Relative-strength & cross-sectional sector ranking (reuse peer universe + scorecard).
3. Forward dividend/yield model: trailing dividend × payout consistency.
4. Ownership-momentum score (above).

**Deliverables:** `CompanyProfileService`, `CompanyProfileController` (`/api/company/{symbol}`), `EventStudyService`, DTOs. No new persistence beyond what marketdata already has.

---

## Phase 2 — Policy engine → Hybrid RAG

**Decision:** Hybrid retrieval (lexical FTS + vector), fused with reciprocal-rank fusion. Rationale: policy text is reference-heavy — lexical preserves exact-match precision (e.g. `RBI/2024-25/123`), vector adds semantic recall ("what affects my IT stocks"). Pure vector would lose reference precision.

### 2.1 — Embeddings on policy chunks
- Add `embedding` column to `PolicyChunk` (pgvector; align dimension with `EmbeddingService` provider).
- Embed on ingest in `PolicyIntelligenceService.ingestDocument()`.
- Backfill job for existing chunks.

### 2.2 — Hybrid retrieval
- New `PolicyHybridRetriever`: run existing lexical FTS query + vector cosine query, fuse via RRF (`score = Σ 1/(k + rank_i)`).
- Route `PolicyIntelligenceService.search()` and `buildAdvisorContext()` through it; keep lexical path as fallback when embeddings absent.

### 2.3 — Stock-level policy bridge (closes the TCS loop)
- Map a stock's sector/factor exposures (via `SymbolExtractorService` + gazetteer) to existing `PolicyImpact` subjects.
- Optionally add `PolicySubjectType.STOCK` for company-specific items.
- Feeds Phase 1 Card 6.

### 2.4 — LLM-driven impact extraction
- Replace `inferImpacts()` keyword rules with an LLM extraction pass (via `LlmRefinementService` thread pool): feed chunk + `PolicyImpact` schema, get structured impacts + reasoning.
- Keep the rule-based extractor as a deterministic fallback when LLM unavailable.

> Note: confirm current embedding-model + LLM model choices against the `claude-api` skill before wiring providers.

---

## Phase 3 — Reactive + temporal policy

### 3.1 — "Policy updates over time"
- **Policy diff:** on supersession, LLM-summarize what changed ("CRR 4.0%→4.5%") → new `PolicyChange` record keyed off `PolicyDocumentVersion`.
- **Topic timeline:** group changes by `subjectKey` (repo-rate, capital-gains-tax, …) into an ordered evolution view. Pair with `RbiPolicyRateProvider` macro series for quantified trajectories.
- Endpoint: `GET /api/policy-intelligence/timeline/{subjectKey}`.

### 3.2 — Event-driven notifications
- On new high-impact policy ingest, match `PolicyImpact.subjectKey` against each user's holdings/sectors (reuse `buildAdvisorContext` matching) → push via `notification` module. Today it is pull-only.

### 3.3 — Falsification monitoring + calibration
- After a policy's effective date, check whether the predicted reaction (`PolicyImpact.falsificationSignal`) actually occurred in market data; feed `ConfidenceCalibrationService` to self-correct future confidence. Mirror `EventOutcomeService`.

---

## Phase 4 — Beginner polish

- **Beginner narration layer** (`InsightNarrationService` as template): one-line plain-language explainer + verdict per metric across all 6 cards. Turns z-scores / Piotroski / EWMA vol into "what this means."

---

## Build sequence (dependency order)

| # | Item | Phase | Effort |
|---|------|-------|--------|
| 1 | `CompanyProfileService` + `/api/company/{symbol}` | 1 | S |
| 2 | Wire corporate actions / events / ownership / deals | 1 | S |
| 3 | Relative strength vs index/sector | 1 | S |
| 4 | Event-study engine | 1 | M |
| 5 | Embeddings on `PolicyChunk` + backfill | 2 | M |
| 6 | Hybrid retrieval (RRF) | 2 | M |
| 7 | Stock-level policy bridge | 2 | S |
| 8 | LLM impact extraction (+ rule fallback) | 2 | M |
| 9 | Policy diff + topic timeline | 3 | M |
| 10 | Event-driven notifications | 3 | S |
| 11 | Falsification + calibration loop | 3 | M |
| 12 | Beginner narration layer | 4 | M |

S = small (wiring), M = medium (new service + math/LLM).

---

## Key principle

Every phase reuses an existing engine. Phase 1 is joins over data you already persist. Phase 2 reuses `EmbeddingService` / `LlmRefinementService`. Phase 3 mirrors `EventOutcomeService` / `ConfidenceCalibrationService`. The scope is integration and surfacing, not rebuilding analytics.
