# Finwise vs Bloomberg Terminal — Honest Assessment & Actionable-Insights Roadmap

_Authored 2026-06-14. Scope: assess the implementation against the Bloomberg-competitor
ambition across financial modelling, mathematical/statistical rigour, and AI integration;
propose actionable insights the existing engine can already generate; and define an
output redesign that makes the system honest by surfacing the computations themselves._

---

## 1. Headline finding

Finwise has a **genuinely rigorous quant engine** wrapped in a **prose-only output layer**.

Every rigorous number computed by `PortfolioRiskService`, `FactorModelService`,
`AttributionService`, `GarchService`, and `MonteCarloGoalService` is serialized into a
text block, handed to an LLM as context, and the **LLM paraphrases it into prose**. The
user never sees the computation — they see an LLM's retelling, which can drop, round,
hedge, or silently omit any number.

> **The fix is architectural, not cosmetic: render computed facts deterministically in
> Java, and let the LLM write only the _interpretation_ — never the numbers.**

This discipline already exists for exactly one thing: the stock `Scorecard.recommendation`
is Java-authoritative and `RESEARCH_SYSTEM_PROMPT` forbids the LLM from overriding it
(`CFOAdvisorService` lines ~132). The honest version of Finwise extends that pattern to
**every** metric.

---

## 2. Scorecard by dimension

| Dimension | Grade | Strengths (verified in code) | Honest gaps |
|---|---|---|---|
| **Financial modelling** | A− | Market-value-weighted risk; adjusted-close returns (corporate actions handled); MCR/CCR risk contributions; ENB; name/sector HHI; diversification ratio; TWRR + Information Ratio vs Nifty; Brinson-Fachler attribution; MF look-through to effective exposures; Monte-Carlo goal funding; LTCG/STCG harvesting | No transaction-cost/slippage model in rebalancing; single-day VaR only; no stress/scenario shocks; no liquidity-adjusted position limits surfaced |
| **Math / statistical rigour** | A− | Ledoit-Wolf shrinkage (gated n≥3); Cornish-Fisher VaR **with validity-domain guard** → parametric fallback; GARCH(1,1) MLE with unconstrained reparameterization + 4 rejection rules → EWMA fallback; Hyndman-Fan R-7 quantiles; geometric annualization | **VaR is never backtested** (no Kupiec/Christoffersen coverage test); factor t-stats self-described "HAC-naive" (no Newey-West); no multiple-testing correction on factor significance |
| **AI integration** | B+ | Multi-provider strategy pattern; async refinement; **outcome-linked RAG (evidence packs of realized event reactions)**; embedding relevance; intent classification; token-budgeted context assembly; **InsightClaim calibration scoreboard** | Confidence scores are **LLM-emitted, not calibrated** to the scoreboard; free-prose output mangles numbers; no typed/validated LLM output; News-Sentiment Risk Score is ad-hoc (hardcoded 30/30/20/20 weights) |
| **Data foundation** | A | Bhavcopy backbone, 3y seed, gap repair, adjusted prices, MF NAV, macro series, policy crawler | — |

**Bottom line:** the engine is ~80% of a credible Bloomberg-lite analytics core for
Indian retail; the product is ~30%, because everything funnels through one prose brief.

---

## 3. The honesty problem, precisely

1. **The LLM is the last mile for numbers.** `appendQuantRiskDecomposition` builds a fully
   caveated block (VaR three ways, shrinkage δ, skew/kurtosis, top contributors), then
   `llmProvider.chat()` reduces it to prose. Nothing guarantees the emailed VaR equals
   `rd.var95CornishFisher()`.
2. **Confidence is theater.** The prompt asks the LLM to emit `Confidence: 0.X`, but
   `InsightEvaluationService` + `InsightClaim` already track realized hit-rates. Confidence
   should be **calibrated** from that scoreboard and the track record shown.
3. **No computation is shown.** A user cannot audit a single number. Bloomberg's
   credibility is drill-down to formula + inputs. Every insight card must ship its method,
   inputs, window, and caveats inline.

---

## 4. Actionable-insights catalog (each maps to math already implemented)

| Insight | Source computation | Example output |
|---|---|---|
| **Risk-budget trim** | `RiskDecomposition.riskContributors` (MCR/CCR) | "HDFCBANK drives 38% of portfolio vol on 16% weight (β=1.16). Trim ₹48k to align risk-contribution with weight." |
| **Concentration in bet-space** | ENB, pairwise corr (`CovarianceEngine`) | "ENB=2.3 across 12 holdings → ~2 independent bets. Redundant cluster {A,B,C}, corr>0.8." |
| **Forward-vol regime** | `GarchService` / `VolForecast` | "10-day GARCH vol 22% vs trailing 16% (α+β=0.94) → vol expansion. Size new adds 30% smaller." |
| **Factor-tilt correction** | `FactorModelService.factorVarianceSharePct`, `systematicSharePct` | "78% of systematic variance = MKT bet (β=1.3) + Financials. SIZE β≈0. You're a leveraged index." |
| **Marginal-add screen** | `StockDeepDive.PortfolioFit.marginalVolImpact`, `correlationToPortfolio` | "ITC @5%: vol −0.4pp (corr 0.21, diversifier). ICICIBANK @5%: +0.6pp (corr 0.83, amplifier)." |
| **Skill verdict** | TWRR + Information Ratio (`PortfolioPerformanceService`) | "TWRR 14.2% vs Nifty 16.1%; active −1.9%, IR −0.4. Stock-picking destroyed value vs an index fund." |
| **Attribution feedback** | `AttributionService` (Brinson-Fachler) | "Allocation +0.8%, Selection −2.1%. Sector ETFs would have beaten your picks." |
| **Tax-harvest calendar** | `CapitalGainsTaxService` / `TaxHarvestingService` | "Harvest XYZ (−₹40k STCG loss) before 31 Mar → ₹6,000 saved. LTCG headroom ₹1.0L." |
| **Goal funding gap** | `MonteCarloGoalService` | "House goal 41% success @ ₹25k/mo (10k paths, μ=11% σ=18%). ₹31.2k/mo → 75%." |
| **Look-through overlap** | `LookThroughService` | "True HDFCBANK 14% = 8% direct + 6% via MFs > 10% cap. Effective sector HHI 0.31 vs 0.22 direct." |
| **VaR coverage honesty** | _needs §6.1 addition_ | "95% VaR breached 18/250 days (expected 12.5); Kupiec rejects → VaR understates tails." |
| **Calibration self-honesty** | `InsightClaim` scoreboard | "55% of BUY/TRIM calls right at 5d (n=40); overconfident ~0.15. Confidence below is calibrated." |

---

## 5. Output redesign: prose brief → structured insight cards

Two-layer output. **Layer 1 rendered 100% in Java (never the LLM). Layer 2 LLM, constrained
to interpretation — forbidden from emitting numbers.**

```
┌─ INSIGHT CARD (Java) ──────────────────────────────────────────────┐
│ ⚠ RISK-BUDGET: Trim HDFCBANK by ₹48,000                            │
│ Why (computed):                                                    │
│   • Risk contribution 38.2% of portfolio vol on 16.1% weight       │
│   • Method: CCRᵢ = wᵢ·(Σw)ᵢ/σₚ ; %RC = CCRᵢ/σₚ                      │
│   • Inputs: 252d adjusted returns, Ledoit-Wolf δ=0.34, β=1.16       │
│   • Window: 2025-06-12 → 2026-06-13 (248 trading days)             │
│   • Caveats: ⚠ 1 holding excluded (INSUFFICIENT_HISTORY, 4.1% NAV) │
│ Calibrated confidence: 0.58 (raw 0.70, scoreboard hit-rate 55%)    │
├─ NARRATIVE (LLM — interpretation only, no new numbers) ────────────│
│ "Your portfolio's swings are dominated by one bank. Trimming…"     │
└────────────────────────────────────────────────────────────────────┘
```

**Charts that are pure renders of existing data structures** (deferred but cheap):
risk-contribution bar; return-distribution histogram with Gaussian vs Cornish-Fisher VaR
overlay; factor-exposure radar; Monte-Carlo goal fan chart; Brinson attribution waterfall;
GARCH vol cone; correlation heatmap.

---

## 6. Roadmap — ranked by leverage (effort/value)

1. **VaR backtest (Kupiec POF + Christoffersen independence).** ~1 service. Turns "we
   compute VaR" into "our VaR is _validated_" — the single biggest honesty upgrade.
2. **Deterministic insight-card renderer + LLM-as-annotator** (§5). Biggest product upgrade.
3. **Calibrated confidence** from the `InsightClaim` scoreboard, replacing LLM-guessed `0.X`.
4. **Newey-West (HAC) standard errors** on factor betas — closes a gap the code already
   admits ("HAC-naive").
5. **Stress scenarios** — apply 2020-COVID / 2024-election-day factor shocks to current
   weights. Cheap given the factor model.
6. **Transaction-cost-aware rebalancing** (brokerage + STT + impact) so "trim ₹48k" is net.

**Recommended first two:** (1) VaR backtest and (2) the insight-card layer — they make
existing numbers _validated_ and _shown_ respectively, and both build on classes that
already exist.
