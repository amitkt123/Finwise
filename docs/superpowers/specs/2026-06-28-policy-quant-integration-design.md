# Policy-to-Quant Integration & Adaptive Risk Model Design

**Date:** 2026-06-28
**Status:** Approved for implementation planning

---

## 1. Problem Statement

Finwise has two mature but disconnected layers:

- **Quantitative models** (risk engine, GARCH, factor model, Monte Carlo, stress tests) that are mathematically sophisticated but read all economic parameters from static config files.
- **Policy intelligence engine** (crawl → chunk → embed → RAG → LLM narration) that correctly identifies RBI/SEBI/PIB events but delivers them only as prose to the LLM — zero quantitative effect.

The result: when RBI raises the repo rate by 25bps, the Sharpe ratios don't change, stress scenario shocks don't update, Monte Carlo volatility stays flat, and NBFC betas don't drift. The LLM may mention the hike, but the math that drives every card and recommendation is blind to it.

This spec closes that gap end-to-end.

---

## 2. Architectural Goal

Introduce a **`QuantitativeMacroState`** service — a single live registry of economic parameters — that all risk models read at runtime instead of static config. A new **`PolicyQuantSignalService`** feeds it: extracting numerical signals from policy events, routing high-confidence signals automatically, and queuing ambiguous ones for admin review.

The result: every policy event that clears a confidence threshold propagates through stress tests, Monte Carlo vol, Kalman betas, and risk-free rate — the same session the event arrives. The LLM's narrative is grounded in math that has already absorbed the policy signal.

**Nothing in the existing math changes.** Every model (GARCH, Ledoit-Wolf, Cornish-Fisher, Brinson-Fachler) remains identical. The change is purely parametric injection — models receive better inputs from a live source instead of a static file.

---

## 3. New Components

### 3.1 `QuantitativeMacroState` (service)

Central parameter registry. All values have a timestamp and a `lastConfirmedBy` field (FBIL / ADMIN / AUTO). Falls back to `RiskProperties` config values when a field has never been set.

| Field | Type | Source | Default |
|---|---|---|---|
| `riskFreeRate` | `double` | FBIL overnight/repo | `RiskProperties.riskFreeRate` |
| `crisisProbability` | `double [0,1]` | `RegimeModelService` (daily fit) | `0.0` |
| `regimeVolCalm` | `double` | HMM fitted calm-state vol | NaN |
| `regimeVolCrisis` | `double` | HMM fitted crisis-state vol | NaN |
| `yieldCurve10y` | `double` | `YieldCurveService` 10y G-sec | NaN |
| `yieldCurveSlope` | `double` | 10y − 1y G-sec spread | NaN |
| `fiiFlowScore` | `double` | `FiiDiiFlowProvider` 20d z-score | `0.0` |
| `policyRateShocks` | `Map<String,Double>` | `PolicyQuantSignalService` confirmed signals | `{}` |

The service is a `@Service` singleton backed by a JPA entity (`MacroStateSnapshot`) for persistence across restarts. Every field write is appended to an audit log (`MacroStateAuditEntry`) with: field name, old value, new value, source, timestamp, confirming user.

Exposed via:
- `GET /api/admin/macro-state` — current state as JSON
- `GET /api/admin/macro-state/audit` — change log

### 3.2 `PolicyQuantSignalService` (service)

Bridges `PolicyIntelligenceService` (text) and `QuantitativeMacroState` (numbers).

**Signal extraction pipeline (per policy event card):**

```
1. Authority + binding level filter
   - {RBI, SEBI, MoF} × {NATIONAL, INSTITUTIONAL} → high-trust path
   - SECTORAL or PIB → medium-trust path
   - Otherwise → informational only, no quant update

2. Extract numerical signal by transmissionChannel:
   - RATE: cross-check FBIL for the rate delta (primary); LLM extraction as backup
   - SECTOR_MARGIN: LLM extracts shock % from document text
   - FII_REGULATORY: magnitude and direction from policy text
   - FISCAL_STIMULUS: sector benefit estimate in % (LLM extraction, lower confidence)
   - LIQUIDITY_RULE: CRR/SLR delta in bps from document text

3. Confidence score:
   confidence = authority_weight × specificity_score × (1 - ambiguity_penalty)
   where:
     authority_weight: RBI=1.0, SEBI=0.95, MoF=0.85, PIB=0.60
     specificity_score: exact number in document=1.0, range=0.7, directional only=0.4
     ambiguity_penalty: 0.0 if single channel, 0.2 per additional conflicting channel

4. Route:
   confidence >= 0.75 AND signal_type in WHITELIST → PolicyQuantSignalQueue (AUTO_APPROVE)
   confidence <  0.75 OR  signal_type not in WHITELIST → PolicyQuantSignalQueue (PENDING)

WHITELIST = {RATE (repo, CRR, SLR), FII_REGULATORY}
FBIL rate signals bypass the queue entirely: the FBIL feed is structured and trusted.
```

**Surprise scaling:** when `surpriseClassification == HIGH_SURPRISE`, sector shock overlays are multiplied by 1.5. When `LOW_SURPRISE`, multiplied by 0.7. This captures the well-documented finding that policy surprises move markets more than anticipated actions.

### 3.3 `PolicyQuantSignalQueue` (JPA entity + repository + admin API)

Solves the RBI scraping reliability problem. The queue decouples data quality from model execution.

**Entity fields:** `id`, `sourceEventCardId`, `parameterKey`, `proposedValue`, `currentValue`, `confidence`, `status` (PENDING / AUTO_APPROVE / CONFIRMED / REJECTED / OVERRIDDEN), `overrideValue`, `createdAt`, `resolvedAt`, `resolvedBy`.

**Admin endpoints:**
- `GET  /api/admin/policy-signals` — paginated list, filterable by status
- `POST /api/admin/policy-signals/{id}/confirm` — apply proposed value
- `POST /api/admin/policy-signals/{id}/override` — apply a different value (body: `{"value": 0.065}`)
- `POST /api/admin/policy-signals/{id}/reject` — discard with reason
- `GET  /api/admin/policy-signals/transmission-table` — download current CSV
- `POST /api/admin/policy-signals/transmission-table` — hot-reload updated CSV

**FBIL as the rate backbone:** `FbilProvider` (already exists) runs on its existing schedule and writes to `QuantitativeMacroState.riskFreeRate` directly — no queue. The FBIL rate is always the authoritative number. Policy documents from RBI add the *context* (which sectors are affected, horizon, surprise level) but cannot contradict the FBIL rate. If FBIL shows +25bps overnight before the RBI statement is scraped, the rate update is already live.

### 3.4 `PolicyTransmissionTable` (config CSV, hot-reloadable)

Maps (policy event type, factor/sector) → default shock adjustment in percent.

```csv
# event_type, factor_or_sector, shock_pct_adjustment
RATE_HIKE_25BPS, BANKING, +1.8
RATE_HIKE_25BPS, NBFC, -3.2
RATE_HIKE_25BPS, REALTY, -4.5
RATE_HIKE_25BPS, RATE_SENSITIVE_SPREAD, -2.8
RATE_CUT_25BPS, BANKING, +2.1
RATE_CUT_25BPS, NBFC, +1.4
SEBI_MARGIN_TIGHTEN, SIZE, -3.8
FII_OUTFLOW_2SIGMA, MKT, -2.1
CRR_HIKE_50BPS, BANKING, -1.2
```

Rows are empirically derived from Indian market episodes 2010–2024 (RBI rate cycles, 2013 taper tantrum, 2020 COVID, 2022–23 hike cycle). Stored at `src/main/resources/data/policy_transmission.csv`. The admin can update and hot-reload without a deploy.

Overlays are **additive and directionally capped**: they can worsen a stress scenario but cannot turn a loss scenario into a gain. The CSV baseline is always the floor.

### 3.5 `KalmanBetaService` (service)

Time-varying beta estimation via a state-space model.

**State space:**
```
β_t = β_{t-1} + η_t,    η_t ~ N(0, Q)     [beta evolves as a random walk]
r_t = Xₜ βₜ + εₜ,       εₜ ~ N(0, R)     [return = factors × beta + idio noise]

Kalman recursion:
  P_{t|t-1} = P_{t-1|t-1} + Q
  Kₜ        = P_{t|t-1} Xₜᵀ / (Xₜ P_{t|t-1} Xₜᵀ + R)
  β_{t|t}   = β_{t|t-1} + Kₜ(rₜ − Xₜ β_{t|t-1})
  P_{t|t}   = (I − KₜXₜ) P_{t|t-1}
```

**Regime-adaptive Q:**
```
Q_eff = Q_base × (1 + crisisProbability × 5)
```
In crisis, betas are allowed to change faster — matching the empirical observation that sector betas spike during rate tightening cycles. `Q_base` is configurable (default: `1e-4`); `R` is initialized from the holding's OLS residual variance.

**Outputs per (symbol, factor) pair:** `currentBeta` (β_{T|T}), `betaDrift` (β_T − β_{T−60d}), `betaHistory` (full smoothed series). Stress scenarios use `currentBeta` (Kalman). Attribution keeps OLS beta (longer-window stability appropriate for performance measurement). Both are reported in `FactorRiskReport.HoldingFactorExposure`.

### 3.6 `FiiFlowFactorService` (service)

Converts FII net flow data into a tradeable factor return series.

```
FII_factor_t = (FII_net_t − μ₂₀) / σ₂₀    [20-day rolling z-score]
```

Orthogonalized against MKT (Nifty) via OLS residual before use, isolating the flow effect beyond market-wide beta. Added to `FactorReturnService.build()` as `FII_FLOW` when flow history is available. Holdings with FII ownership > 20% (from `StockFundamentals.fiiHoldingPct`) automatically include this factor in their per-holding regression.

### 3.7 `MacroStateRefreshJob` (scheduled task)

Runs in `CFOScheduler` after the daily price fetch completes (≈ 4:00 PM IST). Calls:
1. `RegimeModelService.fit()` on Nifty daily returns → writes `crisisProbability`, `regimeVolCalm`, `regimeVolCrisis` to `QuantitativeMacroState`
2. `YieldCurveService` → writes `yieldCurve10y`, `yieldCurveSlope`
3. `FiiFlowFactorService` → writes `fiiFlowScore`

All writes are non-blocking best-effort. If the fit fails (insufficient data), the existing value is kept. Every write is logged to the audit trail.

---

## 4. Model Changes (Parametric Only)

### 4.1 `PortfolioRiskService`

| What changes | Before | After |
|---|---|---|
| Risk-free rate source | `riskProperties.getRiskFreeRate()` | `macroState.getRiskFreeRate()` |
| LVaR (new fields) | Not present | `lvar95`, `lvar99` added to `RiskDecomposition` |
| Regime flag | Not present | `REGIME_ELEVATED` note when `crisisProbability > 0.60` |
| Forward risk vol floor | GARCH only | `max(GARCH_vol, p_crisis × σ_crisis)` |

**LVaR — already computed, not yet surfaced:**
`LiquidityReport` (Phase 9b) already contains `lvar95`, `lvar95Stressed`, and `liquidityCost` using the Corwin-Schultz spread estimator (half-spread × Σ wᵢ V, doubled for the stressed variant). The math is done. What is missing is:
1. `RiskDecomposition` does not carry `lvar95` / `lvar95Stressed` — these need to be added as fields, populated by calling `LiquidityService.compute()` (already called for the trim card, so result can be reused).
2. The brief prompt and insight card do not cite LVaR — the context builder needs to surface it alongside the parametric VaR figure.

No new formula needed. Phase 3 is purely plumbing: read from `LiquidityReport.lvar95()` and write into `RiskDecomposition`.

### 4.2 `MonteCarloGoalService`

| What changes | Before | After |
|---|---|---|
| Drift source | `estimateDriftVol().annualDrift()` | Same, blended down by `p_crisis × stressDriftPenalty` |
| Vol source | `estimateDriftVol().annualVolatility()` | `(1-p) × σ_calm + p × σ_crisis` |
| Drift floor | None | `max(μ_eff, yieldCurve10y − goalInflationRate)` |
| Output | `probabilityOfSuccess`, corpus fan | Same + `regimeAdjusted` flag, `effectiveSigma` |

`stressDriftPenalty` is configurable (default: `0.04` — 4% annual drag in full crisis). Falls back to historical vol if regime state is unavailable (unchanged behavior for users with no Nifty history).

### 4.3 `StressScenarioService`

| What changes | Before | After |
|---|---|---|
| Factor shocks | Static CSV only | `csvShock + policyOverlay` |
| Policy overlay source | None | `macroState.getPolicyRateShocks()` |
| Surprise scaling | None | Overlay × 1.5 (HIGH_SURPRISE), × 0.7 (LOW_SURPRISE) |
| Output | `factorModelPnl`, `betaOnlyPnl` | Same + `policyOverlayApplied`, `overlayNotes` |

Overlay directional cap: `effectiveShock = min(csvShock + overlay, 0)` on the loss side — overlays never artificially improve a scenario.

### 4.4 `FactorModelService`

| What changes | Before | After |
|---|---|---|
| Beta estimation | Full-window OLS only | OLS + Kalman (via `KalmanBetaService`) |
| Factors | MKT, SIZE, sector spreads, SMB/HML | Same + `FII_FLOW` (when available) |
| `HoldingFactorExposure` | OLS beta, t-stats, α, R², idio vol | Same + `kalmanBeta`, `betaDrift` |
| Stress input | OLS beta | Kalman beta |
| Attribution input | OLS beta | OLS beta (unchanged — stability needed) |

### 4.5 `InsightCardService`

Two new card triggers:

**`BETA_DRIFT` watch card:** Emitted when any of the top-3 risk contributors has `betaDrift > 0.30` (beta shifted by more than 0.3 in the last 60 trading days). Title format: *"HDFC BANK beta drifted from 0.92 → 1.31 over 60d (regime: crisis elevated)"*. Severity: WATCH.

**Regime caveat on goal cards:** When `GoalSimulationResult.regimeAdjusted = true`, the goal funding card appends: *"Vol elevated by regime signal (crisis prob 68%) — σ_eff 24.1% vs historical 18.3%. SIP estimates are conservative."*

**Brief prompt update:** When `QuantitativeMacroState` has active policy overlays, the brief rule adds: *"Stress scenarios include the following active policy overlays — cite them when discussing tail risk: [overlay list]."*

---

## 5. Data Flow (End to End)

```
FBIL (structured, daily)
   └──► FbilProvider ──► QuantitativeMacroState.riskFreeRate  [auto, no queue]

RBI / SEBI / PIB documents (scraped / crawled)
   └──► PolicyDocumentCrawlerService
        └──► PolicyIntelligenceService (text layer, unchanged)
             └──► PolicyQuantSignalService
                  ├─ confidence >= 0.75 + WHITELIST ──► PolicyQuantSignalQueue [AUTO_APPROVE]
                  └─ else ──────────────────────────────► PolicyQuantSignalQueue [PENDING]
                                                              ▲
                                                       Admin reviews at
                                                  /api/admin/policy-signals
                                                              │ CONFIRM / OVERRIDE
                                                              ▼
                                              QuantitativeMacroState.policyRateShocks
                                              QuantitativeMacroState.* (all fields)
                                                              │
                    ┌─────────────────────────────────────────┤
                    │                    │                    │
         PortfolioRiskService  MonteCarloGoalService  StressScenarioService
         (riskFreeRate,        (σ_eff, μ_eff,         (policy overlays on
          LVaR, regime flag)    yield floor)           factor shocks)
                    │
         FactorModelService ──► KalmanBetaService (regime-adaptive Q)
                    │
         FiiFlowFactorService ──► FactorReturnService (FII_FLOW factor)
                    │
         MacroStateRefreshJob (daily 4PM)
         ├── RegimeModelService.fit() ──► crisisProbability, σ_calm, σ_crisis
         ├── YieldCurveService ──────────► yieldCurve10y, yieldCurveSlope
         └── FiiFlowFactorService ────────► fiiFlowScore
```

---

## 6. Implementation Phases

### Phase 1 — `QuantitativeMacroState` + FBIL Rate + Admin Queue
*Foundation; everything else depends on it.*

**Deliverables:**
- `QuantitativeMacroState` service + JPA entity + audit log
- `PolicyQuantSignalQueue` entity + repository + admin REST endpoints (list / confirm / override / reject)
- `PolicyQuantSignalService` — rate signals only (FBIL auto-applies; RBI scrape goes to queue)
- `PortfolioRiskService` + `MonteCarloGoalService`: replace static `getRiskFreeRate()` with `macroState.getRiskFreeRate()`

**Value shipped:** Live FBIL-backed risk-free rate. Sharpe and Sortino update the morning after an RBI decision. Admin has a review queue for any ambiguous extractions.

### Phase 2 — Regime-Conditional Monte Carlo + Yield Curve Floor
*Depends on Phase 1.*

**Deliverables:**
- `MacroStateRefreshJob` — runs `RegimeModelService.fit()` + `YieldCurveService` after daily price fetch
- `MonteCarloGoalService`: regime-blended `σ_eff`, `μ_eff`, yield curve real-rate floor
- `PortfolioRiskService`: `REGIME_ELEVATED` flag in `VolForecast.notes()` when `crisisProbability > 0.60`
- `GoalSimulationResult`: new fields `regimeAdjusted`, `effectiveSigma`
- Goal funding card regime caveat in `InsightCardService`

**Value shipped:** Goal success probabilities are honest about the current vol regime. A retirement goal that looks 80% funded at historical vol shows 65% when crisis probability is 70%.

### Phase 3 — LVaR + Policy → Stress Overlay
*Depends on Phase 1.*

**Deliverables:**
- `PolicyTransmissionTable` — CSV loader + hot-reload admin endpoint
- `PolicyQuantSignalService` extended: sector shock extraction for all transmission channels
- `StressScenarioService`: two-layer shock (CSV baseline + policy overlay), `StressResult.policyOverlayApplied`
- `PortfolioRiskService`: `lvar95`, `lvar99` in `RiskDecomposition`; spread reuse from `LiquidityService`
- Brief prompt update: cite active policy overlays in stress discussion

**Value shipped:** Stress tests update the same session as an RBI decision. VaR includes what you'd actually lose selling into a crunch.

### Phase 4 — Kalman Betas + FII Flow Factor
*Depends on Phase 1 and Phase 2 (needs `crisisProbability` for regime-adaptive Q).*

**Deliverables:**
- `KalmanBetaService` — regime-adaptive Q, outputs `currentBeta`, `betaDrift`, `betaHistory`
- `FiiFlowFactorService` — 20-day z-scored flow, MKT-orthogonalized
- `FactorModelService`: run Kalman alongside OLS; `FII_FLOW` factor; updated `HoldingFactorExposure`
- `InsightCardService`: `BETA_DRIFT` watch card when `betaDrift > 0.30` for a top contributor

**Value shipped:** NBFC betas that reflect the current rate cycle, not the 12-month average. FII-sensitivity as a measurable factor exposure.

### Phase 5 — Full Policy Engine Integration + Calibration Loop Closure
*Depends on all prior phases.*

**Deliverables:**
- `PolicyQuantSignalService` complete: all transmission channels (RATE, SECTOR_MARGIN, LIQUIDITY_RULE, FISCAL_STIMULUS, FII_REGULATORY)
- Confidence threshold self-calibration: channels with high historical accuracy get lower auto-approve threshold (read from `ConfidenceCalibrationService` scoreboard)
- Full audit log and admin UI polish
- Brief rule: any brief with active policy overlays must name them explicitly with source and effective date
- Calibration loop: policy-triggered insight cards flow through `InsightEvaluationService` → `EventOutcomeService` → scoreboard → `ConfidenceCalibrationService` feeds back into `PolicyQuantSignalService` thresholds

**Value shipped:** Self-improving policy signal routing. The more the system runs, the more accurately it auto-applies vs routes to review. The LLM's narrative is provably grounded in math that has already priced the policy event.

---

## 7. Testing Strategy

Each phase ships with:

| Test type | Coverage |
|---|---|
| Unit — `PolicyQuantSignalService` | Rate extraction from mock event cards; confidence scoring; routing decisions (auto vs queue) |
| Unit — `KalmanBetaService` | Fixed-seed return series; verify convergence; `betaDrift = 0` on i.i.d. returns |
| Unit — `MonteCarloGoalService` (extended) | `σ_eff` blending arithmetic with injected mock `QuantitativeMacroState` (p=0.0 → calm; p=1.0 → crisis) |
| Unit — `StressScenarioService` (extended) | Overlay cannot make a negative shock positive; overlay scales with surprise factor |
| Unit — `LVaR` | `lvar95 >= var95CornishFisher` for any non-zero spread portfolio |
| Unit — `FiiFlowFactorService` | Orthogonality: `corr(FII_factor, MKT) ≈ 0` after residualization |
| Integration — `QuantitativeMacroState` | Write → persist → restart → read matches pre-restart state |
| Integration — end-to-end policy event | Inject a mock RBI +25bps event → verify `PortfolioRiskService` Sharpe changes, stress overlay appears, regime flag set |

All models accept a mock `QuantitativeMacroState` via constructor injection — no Spring context needed for math unit tests.

---

## 8. Non-Goals

- **No changes to LLM prompts beyond the overlay-citation rule.** The LLM remains in narration-only mode.
- **No replacement of existing math.** GARCH, Ledoit-Wolf, Cornish-Fisher, Brinson-Fachler are unchanged.
- **No real-time intraday macro state updates.** `MacroStateRefreshJob` runs once daily. Intraday policy events land in the queue and wait for the next brief cycle (or admin confirm).
- **No Bloomberg terminal integration.** FBIL is the authoritative rate source; all other feeds use existing providers.
- **No Bayesian state space model replacing the factor model.** Kalman beta is additive to OLS — it does not replace the regression pipeline.
- **No UI beyond admin REST endpoints.** The FE integration guide covers how the FE consumes the new fields (`lvar95`, `kalmanBeta`, `betaDrift`, `regimeAdjusted`, `overlayNotes`).

---

## 9. Open Questions (Resolved)

| Question | Resolution |
|---|---|
| Policy → quant translation: automatic, supervised, or hybrid? | **Hybrid.** FBIL auto-applies. High-confidence known signal types auto-approve. Ambiguous or novel signals queue for admin review. |
| RBI scraping unreliability | **FBIL is the rate backbone.** RBI documents provide context and sector signals only. Queue absorbs scraping failures. |
| Markov-switching vs threshold regime conditioning | **Threshold conditioning.** HMM regime probabilities condition model parameters via linear blending — not structural Markov-switching regression. Simpler, transparent, testable. |
| OLS vs Kalman for attribution | **OLS for attribution** (long-window stability); **Kalman for stress/VaR** (real-time sensitivity). Both reported. |
| Stress overlay directionality | **Overlay is additive loss-only.** Cannot improve a baseline negative scenario. |
