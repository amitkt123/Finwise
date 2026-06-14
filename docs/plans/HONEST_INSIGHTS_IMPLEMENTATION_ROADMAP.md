# Honest-Insights Implementation Roadmap

_Companion to `HONEST_INSIGHTS_ASSESSMENT.md`. Turns the six roadmap items into
concrete, sequenced engineering phases with new files, signatures, integration points,
and acceptance criteria. All work builds on existing services — no new data infra._

---

## Guiding principle (applies to every phase)

> **Numbers are rendered in Java. The LLM only writes interpretation.**
> Every metric that reaches the user must carry: value · method · inputs · window · caveats.

This already exists for `Scorecard.recommendation` (Java-authoritative, LLM forbidden to
override — `CFOAdvisorService` `RESEARCH_SYSTEM_PROMPT`). We generalize that pattern.

---

## Phase sequence & dependencies

```
A. VaR Backtest ─────────────┐  (independent, fast honesty win — DO FIRST)
                             │
B. Insight-Card Layer ───────┼──> C. Calibrated Confidence ──> (surfaces on cards)
   (B1 model+renderer        │
    B2 generators            ├──> E. Stress Scenarios (renders as a card)
    B3 narration+validator   │
    B4 wire into brief)      └──> F. Txn-Cost-Aware Rebalancing (enhances risk-budget card)

reD. Newey-West HAC SEs ───────── (independent, localized — parallelizable anytime)
```

Recommended order: **A → B → C → F → E**, with **D** slotted in parallel.

---

## Phase A — VaR Backtest (Kupiec POF + Christoffersen)

**Goal:** validate the VaR you already compute. Converts "we compute VaR" → "our VaR is
coverage-tested."

**New files**
- `cfo/service/analytics/VarBacktestService.java`
- `cfo/model/VarBacktestReport.java` (record)
- `test/.../VarBacktestServiceTest.java`

**Design**
- Source series: reuse `PortfolioRiskService.portfolioReturnSeries(userId)` (package-private,
  same `analytics` package — returns the aligned value-weighted `double[]`). For a rolling
  test, at each day `t ≥ W` estimate VaR from trailing window `[t−W, t−1]` and flag a breach
  when `return[t] < −VaR_t`. Default `W = 250`, `p = 0.05` and `0.01`.
- **Kupiec POF (unconditional coverage):** `π̂ = x/T`;
  `LR_uc = −2·ln[ (1−p)^(T−x)·p^x / ((1−π̂)^(T−x)·π̂^x) ]` ~ χ²(1).
- **Christoffersen independence:** transition counts n00,n01,n10,n11 → `LR_ind` ~ χ²(1)
  (detects breach clustering).
- **Conditional coverage:** `LR_cc = LR_uc + LR_ind` ~ χ²(2).
- Use Commons-Math `ChiSquaredDistribution` for p-values (already a dependency).

**Record shape**
```java
record VarBacktestReport(int window, double confidenceLevel, int observations, int breaches,
    double expectedBreachRate, double actualBreachRate,
    double kupiecLR, double kupiecPValue, boolean kupiecReject,
    double christoffersenLR, double christoffersenPValue, boolean clusteringDetected,
    String verdict) {}  // verdict: "VaR well-calibrated" | "VaR understates tails" | ...
```

**Integration:** add `appendVarBacktest(...)` to `CFOAdvisorService`, rendered immediately
after `appendQuantRiskDecomposition`. Test both VaR levels; surface which VaR variant
(parametric/CF/historical) is being validated.

**Acceptance**
- Synthetic N(0,σ) returns → breach rate ≈ p, Kupiec fails-to-reject (p>0.05).
- Fat-tailed (t-dist) returns vs parametric VaR → Kupiec rejects.
- Clustered-vol synthetic → Christoffersen flags clustering.

**Effort:** ~0.5 day.

---

## Phase B — Insight-Card Layer (the backbone)

**Goal:** replace the single prose dump with typed, Java-rendered insight cards; LLM
constrained to interpretation. This is the largest phase; ship it in 4 sub-steps.

### B1 — Model + renderer
**New files**
- `cfo/model/InsightCard.java`, `cfo/model/Computation.java` (records)
- `cfo/service/insight/InsightCardRenderer.java` (card → markdown)

```java
record InsightCard(String id, Category category, Severity severity,
    String title,            // Java-rendered, e.g. "Trim HDFCBANK by ₹48,000"
    String actionVerb,       // trim/add/hold/watch/...  (nullable)
    String symbol,           // nullable
    List<Computation> computations,
    List<String> caveats,
    double rawConfidence,
    Double calibratedConfidence,   // null until Phase C
    String trackRecord,            // null until Phase C
    String narrative) {            // null until B3 (LLM)
  enum Category { RISK_BUDGET, CONCENTRATION, VOL_REGIME, FACTOR_TILT, SKILL,
                  ATTRIBUTION, TAX, GOAL, LOOKTHROUGH, MARGINAL_ADD, VAR_BACKTEST, STRESS }
  enum Severity { INFO, WATCH, ACTION, ALERT }
}
record Computation(String label, String value, String method, String inputs, String window) {}
```
Renderer emits the §5 card block **and** a machine-parseable action line
`- SYMBOL verb: reason — Confidence: 0.X` so `InsightEvaluationService.extractClaims`
keeps working unchanged.

### B2 — Generator catalog
**New file:** `cfo/service/insight/InsightCardService.java` — one generator per insight in
the assessment §4, each pulling from an existing service and formatting numbers in Java:

| Generator | Source |
|---|---|
| `riskBudgetCards()` | `RiskDecomposition.riskContributors` (MCR/CCR/%RC) |
| `concentrationCard()` | `effectiveNumberOfBets`, `CovarianceEngine` pairwise corr |
| `volRegimeCard()` | `PortfolioRiskService.forwardRisk()` → `VolForecast` |
| `factorTiltCard()` | `FactorRiskReport.factorVarianceSharePct`, `systematicSharePct` |
| `marginalAddCard(symbol)` | `StockDeepDive.PortfolioFit.marginalVolImpact`, `correlationToPortfolio` |
| `skillVerdictCard()` | `PortfolioPerformanceService.computeTwrr` (active return, IR) |
| `attributionCard()` | `AttributionService` (Brinson-Fachler) |
| `taxHarvestCard()` | `TaxHarvestingService` / `CapitalGainsTaxService` |
| `goalFundingCards()` | `MonteCarloGoalService` |
| `lookThroughCard()` | `LookThroughService` |
| `varBacktestCard()` | Phase A `VarBacktestService` |

`generate(userId)` returns `List<InsightCard>` ordered by severity (ALERT→INFO). Each
generator is independently testable and degrades to "skip" when its source returns empty.

### B3 — Constrained narration + honesty validator
**New file:** `cfo/service/insight/InsightNarrationService.java`
- One LLM call per brief: send cards as JSON, prompt = "Write a 1–2 sentence interpretation
  per card id. **Do NOT introduce any number, %, or ₹ figure not present in that card's
  computations.** Return JSON `{cardId: narrative}`."
- **Validator:** tokenize numerics in the returned narrative; if any numeric token is absent
  from the card's `computations` values → strip the sentence / fall back to a templated
  interpretation. This is the structural honesty guarantee — the LLM can no longer be the
  source of truth for a number.

### B4 — Wire into brief + REST
- `generateDailyBrief()` / `generateMarketInsight()` → `cards = cardService.generate()`,
  `narrationService.narrate(cards)`, `content = renderer.toMarkdownBrief(header, cards)`.
  Cards render with numbers intact even if the LLM call fails.
- New endpoint `GET /api/cfo/insight-cards` → `List<InsightCard>` (JSON, for a future
  charts UI), mirroring the existing `/look-through` and `/attribution` endpoints in
  `CFOController`.

**Acceptance**
- Test: rendered VaR string is byte-identical to `String.format(...rd.var95CornishFisher())`.
- Test: narration validator strips a hallucinated number injected into a stub LLM response.
- Test: with a stubbed empty engine, `generate()` returns `[]` and the brief still renders a
  "insufficient data" header (no crash, no fabricated numbers).

**Effort:** ~2–3 days total (B1 0.5, B2 1–1.5 incremental, B3 0.5, B4 0.5).

---

## Phase C — Calibrated Confidence

**Goal:** replace LLM-guessed `Confidence: 0.X` with a value calibrated to the actual
track record, and show that track record.

**New file:** `cfo/service/insight/ConfidenceCalibrationService.java`
- Source: `InsightEvaluationService.calibrationReport(sinceDays)` already returns
  `CalibrationRow(provider, promptVersion, horizon, n, hits, hitRate, avgConfidence, brierScore)`.
- `calibrate(rawConfidence, provider, horizon)` → sample-size-shrunk estimate:
  `calibrated = (n·hitRate + k·rawConfidence) / (n + k)`, `k≈10` (shrinks to raw when n small).
- `trackRecord(provider, horizon)` → `"scoreboard: 55% hit @5d, n=40 (overconfident by 0.15)"`.
- Wire both into `InsightCard.calibratedConfidence` + `trackRecord` in `InsightCardService`.

**Acceptance**
- Cohort with hitRate < avgConfidence → calibrated < raw (overconfidence corrected).
- Small-n cohort → calibrated ≈ raw (shrinkage dominates). Unit test both.

**Effort:** ~0.5 day. Computation independent of B; surfacing depends on B1.

---

## Phase D — Newey-West (HAC) Standard Errors on Factor Betas

**Goal:** close the gap the code already admits — `FactorModelService` renders
"t-stats are HAC-naive."

**Modify:** `cfo/service/analytics/FactorModelService.java`
- Replace OLS SE with Newey-West HAC: lag `L = floor(4·(T/100)^(2/9))`; adjust residual
  covariance with Bartlett-weighted autocovariances `w_l = 1 − l/(L+1)`.
- Recompute per-holding `isSignificant(factor)` on the HAC t. Update render note:
  "HAC-naive" → "Newey-West HAC, L=k".

**Acceptance**
- AR(1)-error synthetic regression → HAC SE > OLS SE; significance flags shift accordingly.
- Render note shows the chosen lag. Unit test.

**Effort:** ~0.5 day, localized to one service + its test.

---

## Phase E — Stress Scenarios

**Goal:** apply named historical shocks to current weights.

**New files**
- `cfo/service/analytics/StressScenarioService.java`
- `resources/stress_scenarios.csv` (factor-shock vectors per scenario)

**Design**
- Scenarios: `COVID_2020_03_23`, `ELECTION_2024_06_04`, `TAPER_2013`, plus a parametric
  `−2σ Nifty day`. Each = a factor-return vector.
- Two estimates per scenario: factor-model P&L `Σ β_f·shock_f·V` (from
  `FactorRiskReport.portfolioFactorBetas`) and a beta-only cross-check `β·indexShock·V`.
- Render as a `STRESS` `InsightCard` (post-B): "COVID replay: Nifty −13.0% → est P&L
  −₹X (β=1.25 + Financials tilt); beta-only cross-check −₹Y."

**Acceptance:** single-factor portfolio reproduces `β·shock·V` exactly. Unit test.

**Effort:** ~0.5–1 day.

---

## Phase F — Transaction-Cost-Aware Rebalancing

**Goal:** make "trim ₹X" net of costs, and suppress trades whose risk benefit < cost.

**New file:** `cfo/service/analytics/TradingCostService.java`
- `estimate(symbol, side, notional)` → brokerage + STT (0.025% sell delivery / 0.1% buy) +
  exchange txn + SEBI + GST + stamp + **impact** (source ADV/spread from the existing
  `LiquidityService` / `LiquidityReport`).
- Enhance `riskBudgetCards()`: show gross trim, est cost breakdown, and net %RC reduction;
  drop the card when `riskBenefit < cost`.

**Acceptance:** cost math matches the published STT/brokerage schedule for a worked example;
sub-threshold trims are suppressed. Unit test.

**Effort:** ~0.5–1 day.

---

## Cross-cutting notes

- **Persistence:** `InsightCard` is generated on demand — no new table needed. (Optional
  later: persist for a card-history view; Hibernate `ddl-auto=update` would auto-create it.)
- **Backward compat:** keep emitting the machine-parseable action line inside cards so
  `InsightEvaluationService.extractClaims` (and the calibration loop it feeds) keep working.
- **Scheduler:** no new jobs; cards regenerate inside the existing brief cadence. The
  nightly `scoreMaturedClaims()` already feeds Phase C.
- **Testing bar:** every new service ships with a unit test using synthetic series; the
  existing suite (37 risk tests green) is the model.

## Definition of done (per phase)
1. New service + model compile, `./mvnw test` green.
2. Numbers in output are byte-identical to engine values (asserted in a test).
3. Rendered block carries method · inputs · window · caveats.
4. Degrades safely (empty source → skipped card, never a fabricated number).

## Suggested first PR
**Phase A (VaR backtest) + B1 (card model + renderer)** — smallest shippable slice that
both validates an existing number and lays the card backbone everything else renders into.
