# Bloomberg-Parity Remediation Plan

**Status:** Proposed — 2026-06-14
**Context:** Acts on the external assessment that Finwise is a "sophisticated Indian
equity intelligence platform, not yet a Bloomberg competitor." This plan addresses the
**fixable** gaps — financial-modelling depth, statistical rigour, and AI integration.
Gaps that are structurally blocked by **licensed/real-time data** are documented in
§"Out of Scope" and are explicitly **not** scheduled.

Naming: items are `BPR-n` (Bloomberg-Parity Remediation). Effort is engineering days for
one developer. Each item lists rationale, files, approach, tests, and acceptance.

---

## Guiding principle

Do not chase Bloomberg's *moat* (real-time multi-asset feeds, OMS, network effects). Chase
**analytics depth that the Indian retail/RIA competitive set (Smallcase, Kuvera,
Tickertape) does not have.** Every item below is achievable on our existing **EOD batch
data stack** (NSE bhavcopy CM + F&O, AMFI NAV, fundamentals, FBIL/RBI rates) — no new data
licence required, with two narrow caveats called out inline.

---

## Tier 1 — Highest ROI, ship first

### BPR-1 · XIRR / Money-Weighted Return  *(effort: 2d)*

**Why.** TWRR exists and is SEBI-grade, but for a SIP-heavy retail portfolio it answers
the wrong question. Investors want "what return did *my rupees* actually earn?" — that is
XIRR (annualised IRR over irregular cash flows). This is the single most-requested retail
number and the biggest perceived gap.

**Files.**
- New: `cfo/service/analytics/MoneyWeightedReturnService.java`
- Reuse cash flows from `TransactionRepository.findBuySellTransactionsAsc(userId)` and the
  terminal value from `PortfolioSnapshotRepository` (same sources `PortfolioPerformanceService`
  already uses).
- Wire result into `CFOAdvisorService` portfolio context and `DashboardController`.

**Approach.** Build the dated cash-flow vector (BUY = −, SELL = +, terminal market value =
+ on `today`). Solve `Σ CFᵢ / (1+r)^(dᵢ/365) = 0` for `r`. Use **Newton-Raphson** seeded at
0.1 with an **analytic derivative**; fall back to **bisection** on `[-0.9999, 10]` when
Newton fails to converge or the derivative vanishes (sign-change guaranteed bracket). Handle
the degenerate all-same-sign case → return empty. Return both XIRR and absolute MWR.

**Tests.** Golden cases vs. a spreadsheet XIRR: single lump sum, monthly SIP, SIP +
partial redemption, loss-making portfolio (negative root), single-flow degenerate.

**Acceptance.** `/api/dashboard` and the daily brief show portfolio XIRR alongside TWRR,
agreeing with Excel `XIRR()` to ≤ 1 bp on the golden set.

---

### BPR-2 · Wire max-drawdown + Calmar ratio  *(effort: 0.5d)*

**Why.** `CovarianceEngine.maxDrawdown()` (line 189) is fully implemented but **dead code** —
never surfaced. Calmar (annualised return ÷ |max drawdown|) is a trivial derived metric.
Obvious gap, near-zero effort.

**Files.**
- `cfo/model/RiskDecomposition.java` — add `maxDrawdown`, `calmarRatio` fields.
- `cfo/service/analytics/PortfolioRiskService.java` — compute drawdown from the portfolio
  value/return series it already builds (`monthlyPortfolioReturns` / snapshot series),
  derive Calmar using the annualised TWRR from BPR-1 (or annualised mean return as fallback).
- Surface in brief + dashboard.

**Tests.** Unit: monotonic-up series → drawdown 0, Calmar via known return; a series with a
known peak-to-trough → exact drawdown.

**Acceptance.** Drawdown and Calmar appear in `RiskDecomposition` and the brief risk block.

---

### BPR-3 · Structured LLM output (JSON schema) for InsightClaims  *(effort: 3d)*

**Why.** Today the brief is free-form markdown and `InsightEvaluationService` re-parses it
with keyword regex (`detectDirection`, `BULLISH_WORDS`) — lossy and provider-fragile. A
structured claims block makes the calibration loop (our genuine differentiator) precise and
removes hallucinated/un-parseable lines.

**Files.**
- `cfo/service/llm/LLMProvider.java` — promote `chatJson` to first-class; add an overload
  taking a JSON-schema hint. Already have `ClaudeProvider`, `OpenAIProvider`, `OllamaProvider`,
  `GoogleAIProvider`, `OpenRouterProvider` — wire native structured output where supported
  (Claude tool-use / OpenAI `response_format` json_schema / Ollama `format`).
- `cfo/service/CFOAdvisorService.java` — keep the human-readable brief, but additionally
  request a machine block: `claims: [{symbol, direction, horizon, confidence, thesis}]`.
- `cfo/service/InsightEvaluationService.java` — prefer the structured block; keep the regex
  path as a fallback for providers without JSON mode (defensive parse already assumed).

**Approach.** Two-output pattern: prose brief for the human, strict-JSON claims for the
machine, validated against a schema; reject/repair on parse failure (one retry). Do **not**
rip out the markdown brief — only add the structured side-channel.

**Tests.** Schema-validation unit tests; a fixture brief → exact claim set; malformed JSON →
graceful fallback to regex extraction. Verify dedupe still respects the
`uq_claim_insight_symbol_horizon` constraint.

**Acceptance.** Claims for JSON-capable providers come from the structured block; parse
failure rate on the eval scoreboard drops to ~0.

---

## Tier 2 — Differentiating depth

### BPR-4 · Options analytics: Black-Scholes-Merton + Greeks + implied vol  *(effort: 5d)*

**Why.** F&O is the overwhelming majority of NSE volume. Even without real-time chains, EOD
analytics (Greeks, IV per contract, basic IV smile from EOD F&O bhavcopy) opens the entire
F&O-active segment and is computable today.

**Data note.** NSE publishes the **EOD F&O bhavcopy free** — strikes, expiries, settlement
prices. That is sufficient for **EOD** Greeks and an **EOD** IV smile/term structure. A
*real-time* live vol surface is **out of scope** (needs a licensed tick feed — see §Out of
Scope). Spot = EOD close; risk-free `r` from existing FBIL/G-sec curve; dividend yield `q`
default 0 (or index dividend yield where known).

**Files.**
- New package `cfo/service/analytics/options/`:
  - `BlackScholesService.java` — European call/put price; Greeks: delta, gamma, vega, theta,
    rho; use `erf`-based `N(·)` (Apache Commons Math `Erf`/`NormalDistribution`).
  - `ImpliedVolatilityService.java` — invert price→σ via **Newton on vega** with
    **bisection fallback** on `[1e-4, 5.0]`; flag no-arbitrage violations.
  - `OptionChainService.java` — read EOD F&O bhavcopy (extend marketdata ingestion), build
    per-expiry IV smile and ATM term structure.
- Optional new ingestion in `marketdata/` for the F&O bhavcopy file (mirror the CM bhavcopy
  reader pattern in `EodIngestionService`).

**Tests.** BSM price vs. published textbook values; put-call parity holds; Greeks vs.
finite-difference bumps within tolerance; IV round-trip (price→σ→price) recovers input.

**Acceptance.** Given an EOD option row, the system returns price, full Greeks, and IV; an
ATM IV term-structure is available per underlying.

---

### BPR-5 · Asymmetric volatility: GJR-GARCH / EGARCH  *(effort: 3d)*

**Why.** `GarchService` is GARCH(1,1) only. Indian equities show pronounced crash-skew
(leverage effect) — symmetric GARCH understates downside vol in stress. GJR/EGARCH captures
the asymmetry and improves VaR accuracy in exactly the regimes that matter.

**Files.**
- `cfo/service/analytics/GarchService.java` — add a GJR-GARCH(1,1,1) variant:
  `σ²_t = ω + (α + γ·1[ε_{t-1}<0])·ε²_{t-1} + β·σ²_{t-1}`, MLE via the same Nelder-Mead +
  unconstrained reparameterisation already in place; keep the EWMA fallback gates.
- `cfo/config/RiskProperties.java` — flag to select symmetric vs. asymmetric; default to
  fitting both and picking by **AIC/BIC**.
- `cfo/model/VolForecast.java` — record the chosen model + leverage coefficient γ.

**Tests.** On a synthetic series with a known leverage effect, γ̂ > 0 and asymmetric model
wins on AIC; on symmetric data γ̂ ≈ 0 and selection prefers GARCH(1,1); persistence
`α+γ/2+β < 1` enforced.

**Acceptance.** Vol forecasts use the better-fitting model by information criterion;
leverage coefficient is reported in the risk block.

---

### BPR-6 · True Fama-French SMB / HML factors  *(effort: 5d)*

**Why.** The current "factor model" uses MKT + SIZE (midcap-index spread) + own-sector
spread. Attributing alpha against *your own sector* is circular. Real SMB/HML built from the
NSE cross-section makes factor attribution defensible.

**Data note.** Fully buildable from owned data: bhavcopy gives daily prices + shares
outstanding → **market cap (size)**; `StockFundamentals` gives book value → **book-to-market
(value)**. No new licence. Caveat: factor returns are only as deep as our fundamentals
history (3y seed) — disclose the lookback in output.

**Files.**
- `cfo/service/analytics/FactorReturnService.java` — add `buildFamaFrench(since)`:
  - Each rebalance (monthly), rank the liquid NSE universe by size and by B/M.
  - Form 2×3 portfolios (Fama-French 1993); **SMB** = avg(small) − avg(big),
    **HML** = avg(high B/M) − avg(low B/M), value-weighted within bucket.
- `cfo/service/analytics/FactorModelService.java` — regress excess returns on
  {MKT, SMB, HML} (and momentum WML as a stretch); keep Ledoit-Wolf pre-regression when N≥3.
- Keep the existing index-spread factors as a labelled fallback when fundamentals are thin.

**Tests.** Construction unit test on a synthetic universe with planted size/value premia →
recovers positive SMB/HML; loadings on a known-tilt portfolio have expected sign.

**Acceptance.** Factor report shows genuine SMB/HML loadings + t-stats; the brief no longer
attributes alpha to a self-referential sector factor.

---

### BPR-7 · Bond analytics: YTM, duration, convexity  *(effort: 3d)*

**Why.** `BOND` is an enum entry with zero math. `YieldCurveService` reads hardcoded G-sec
tenors as a macro signal, not instrument pricing. Adding clean fixed-coupon bond math closes
an obvious "13 asset types, one of them is a stub" gap.

**Data note.** Computable from clean instrument inputs (coupon, face, frequency, settlement,
maturity, clean/dirty price) — user-entered or from the existing G-sec curve for valuation.
Full corporate-credit depth (credit curves, OAS, ratings transitions) is **out of scope**
(needs licensed credit data).

**Files.**
- New `investment/service/BondAnalyticsService.java`:
  - YTM via Newton/bisection on the price↔yield relation.
  - Macaulay & modified duration, convexity, DV01, accrued interest (30/360 & ACT/ACT).
  - Optional: price a G-sec off the existing `YieldCurveService` zero curve.
- Surface in `InvestmentService` P&L/analytics for `BOND` holdings.

**Tests.** YTM round-trip vs. price; par bond → YTM = coupon; duration/convexity vs. closed
form for a zero-coupon; accrued-interest day-count cases.

**Acceptance.** A `BOND` holding reports YTM, modified duration, convexity, and DV01.

---

## Tier 3 — Statistical polish

### BPR-8 · Statistical regime detection (2-state Gaussian HMM)  *(effort: 5d)*

**Why.** `MacroStateService` infers market state from deterministic FII-flow + yield-curve
thresholds. A 2-state (calm/crisis) Markov-switching model on index returns lets us
*condition* risk estimates on inferred state and gives a probabilistic regime signal instead
of a hard rule.

**Files.**
- New `cfo/service/macro/RegimeModelService.java` — 2-state Gaussian HMM (Hamilton filter +
  Baum-Welch EM) on Nifty daily returns; output smoothed crisis probability + expected
  durations. Pure Java (no new heavy dep), mirroring the hand-rolled MLE style of
  `GarchService`.
- Integrate the regime probability as an input/cross-check to `MacroStateService` (do not
  delete the rules — ensemble them).

**Tests.** On a synthetic two-regime series with known means/vols, EM recovers parameters
and the high-vol state aligns with the planted crisis window.

**Acceptance.** Brief macro block reports a probabilistic regime ("crisis prob 0.18") backed
by the HMM rather than only threshold rules.

---

### BPR-9 · Stale-price correction in covariance/beta  *(effort: 2d)*

**Why.** `CovarianceEngine` aligns on intersection-of-dates (correct) but illiquid midcaps
trade infrequently → stale prices bias beta **downward** and understate covariance.

**Files.**
- `cfo/service/analytics/CovarianceEngine.java` — add **Dimson beta** (sum of slopes on
  lead/lag market returns) for low-liquidity names; flag names where traded-value (already
  computed via `averageTradedValue`) falls below a threshold and apply the correction.

**Tests.** Synthetic stale series (returns lagged) → Dimson beta recovers the true beta that
naive OLS under-estimates.

**Acceptance.** Beta for flagged illiquid names uses the lead/lag correction; the risk
report notes when it was applied.

---

### BPR-10 · XIRR-based outcome scoring for return-magnitude claims  *(effort: 1d)*

**Why.** `InsightClaim` currently scores only **direction** vs. Nifty excess return. Once
BPR-1 lands, return-magnitude claims ("expect ~8% over 3 months") can be scored against the
realised holding-period return, sharpening the calibration scoreboard.

**Files.**
- `cfo/model/InsightClaim.java` — optional `expectedReturn` field (parsed from the BPR-3
  structured block).
- `cfo/service/InsightEvaluationService.java` — when present, score magnitude error
  (realised − expected) and fold into the calibration row alongside hit rate / Brier.

**Tests.** Claim with stated expected return → magnitude error computed against a known
realised window.

**Acceptance.** Calibration report distinguishes directional accuracy from magnitude
calibration per provider/prompt-version.

---

## Out of Scope — blocked by licensed / real-time data (documented, not scheduled)

These are **not code problems**; they require data licences, exchange agreements, or
infrastructure we do not have. Recorded here so the gap is explicit and we don't pretend
otherwise.

| Capability | Why blocked |
|---|---|
| Real-time tick / WebSocket / SSE price feeds | Requires NSE co-location / licensed real-time feed + exchange fees. Whole stack is EOD batch by design. |
| **Live** options vol surface (intraday IV) | Needs real-time option chain feed. (EOD Greeks/IV *are* in scope — BPR-4.) |
| Corporate-credit depth: credit curves, OAS, ratings-transition matrices | Requires licensed credit/ratings data. (Govvie/clean-bond math *is* in scope — BPR-7.) |
| Global multi-asset coverage (FX, global rates/equity/commodities) | Licensed global data; product scope is Indian markets. |
| Execution / OMS connectivity, FINRA/MiFID compliance reporting | Institutional integration + regulatory licensing, not analytics. |
| Network-effect community (counterparty messaging) | Adoption/market-structure moat, not buildable. |
| Higher-dim financial-domain embeddings | Not licensing — model-availability/infra. Swappable later; current 768-d `nomic-embed-text` is adequate for dedup. Tracked as a backlog spike, not this plan. |

---

## Suggested sequencing

1. **Sprint 1 (Tier 1):** BPR-1, BPR-2, BPR-3 → biggest perceived gaps + sharpens the AI
   calibration moat. ~5.5d.
2. **Sprint 2 (Tier 2a):** BPR-4 (options) + BPR-7 (bonds) → opens new user segments. ~8d.
3. **Sprint 3 (Tier 2b):** BPR-5 (asymmetric GARCH) + BPR-6 (Fama-French) → credibility of
   risk/attribution. ~8d.
4. **Sprint 4 (Tier 3):** BPR-8, BPR-9, BPR-10 → statistical polish + closes the eval loop. ~8d.

Total ~30 engineering-days to move from "sophisticated retail intelligence platform" to
"analytics depth no Indian retail competitor matches," within the EOD-data constraint.

---

## Verification discipline (all items)

- Every new quant routine ships with **golden-value unit tests** against an independent
  reference (Excel/textbook/finite-difference), matching the existing rigour of the GARCH /
  Ledoit-Wolf / VaR code.
- Numerical solvers (XIRR, IV, YTM) must have a **bracketed fallback** and degrade to an
  empty/flagged result rather than returning a garbage root.
- Nothing that depends on out-of-scope licensed data is asserted as available — outputs
  carry data-lookback / data-source notes (matching the existing "always with a note"
  fallback convention).
