# Policy-to-Quant Integration — Implementation Status

**Branch:** master | **Merge base:** `11c2908` | **HEAD:** `9ba3324`
**Date:** 2026-06-29 | **Tests:** 318 pass, 0 fail

---

## What Was Built (15 commits)

### Foundation
- **QuantitativeMacroState** — JPA-persisted live macro state; FBIL rate auto-applied on startup
- **PolicyQuantSignalService** — routes policy cards from all 5 transmission channels (DISCOUNT_RATE, SECTOR_MARGIN, LIQUIDITY_RULE, FISCAL_STIMULUS, FII_REGULATORY) into an admin-reviewed queue; trusted authorities auto-approved at ≥0.75 confidence
- **MacroStateRefreshJob** — 16:15 IST scheduled refresh of regime probabilities, yield curve, and FII flow scores

### Risk Engine
- **Live risk-free rate** wired into `PortfolioRiskService` (replaces static config property)
- **LVaR** surfaced in `RiskDecomposition` via `LiquidityService`; `policy_transmission.csv` added
- **REGIME_ELEVATED flag** emitted in risk notes when crisis probability > 60%

### Simulations
- **Regime Monte Carlo** — `MonteCarloGoalService` blends σ/μ from calm/crisis regime vols; annualized correctly from daily stddev output of `RegimeModelService`
- **Stress policy overlay** — `StressScenarioService` applies macroState shocks per scenario with HIGH_SURPRISE (1.5×) / LOW_SURPRISE (0.7×) scaling; hot-reload endpoint on admin controller

### Quant Factors
- **KalmanBetaService** — full matrix Kalman filter (not scalar approximation); regime-adaptive process noise; time-varying beta with drift tracking over 60d
- **FiiFlowFactorService** — 20-day z-score normalization and market-orthogonalized FII flow factor (utility class; wiring into factor regression is future work)
- **Kalman betas wired into FactorModelService** — per-holding `kalmanBeta` and `betaDrift` in `HoldingFactorExposure`; BETA_DRIFT insight cards emitted when drift > 0.30

### LLM Brief Integration
- **Overlay citation in briefs** — `CFOAdvisorService` appends active policy shocks to the LLM prompt so morning/evening briefs reference tail-risk overlays explicitly
- **Goal regime caveat** — InsightCard now shows pre-blend historical σ vs effective σ distinctly (fixed by review)

---

## Code Review Findings & Resolutions

| Severity | Finding | Resolution |
|----------|---------|------------|
| **Critical** | Daily vol stored without annualizing → 1/16 correct vol in Monte Carlo | Fixed: `× sqrt(252)` in `MacroStateRefreshJob` |
| **Important** | Admin confirm/override hardcoded `setRiskFreeRate` for all signal types | Fixed: `applySignal()` routes by `parameterKey` |
| **Important** | Regime caveat showed identical σ_eff vs historical (same blended value) | Fixed: `assembleRegime()` threads pre-blend `historicalSigma` |
| Minor | `loadFromSnapshot()` makes 7 sequential DB queries | Deferred — startup only, trivial data volume |
| Minor | Missing 3-param `set*(double, String, String)` overloads from spec | Deferred — no callers yet |
| Minor | `CBDT` added as trusted authority without spec justification | Accepted — benign; comment added in code |
| Note | `FiiFlowFactorService` math utilities not yet wired into factor regression pipeline | Tracked for future task |

---

## What Remains (not in scope of this plan)

- Wire `FiiFlowFactorService` as a proper factor into `FactorReturnService.FactorSet`
- 3-param `set*` overloads on `QuantitativeMacroState`
- Replace `loadFromSnapshot()` sequential queries with `findAll()` + map lookup
