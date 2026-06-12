# Finwise Financial Modelling Reference

This document describes the financial modelling implemented in the Finwise CFO and investor research engine. It is intended to be the operating reference for future development, testing, and review.

Finwise uses deterministic Java services for calculations and uses the LLM only as a renderer over computed context. The LLM must not calculate financial ratios, invent market data, override scorecard recommendations, or fill missing fields from model memory.

## Scope

The modelling layer currently covers:

- Portfolio risk decomposition for Indian equity portfolios.
- Canonical adjusted-close return series.
- Technical analysis indicators.
- Fundamentals and valuation snapshots.
- Evidence-weighted stock research scorecards.
- News and macro catalyst scoring.
- Portfolio-fit modelling for candidate positions.
- Data quality and confidence gating.

The implementation is pragmatic v1, not a full institutional data platform. Free/public feeds are used by default, with adapter boundaries for paid or normalized market data later.

## Core Design Principles

1. **Java computes, LLM explains**
   - Quantitative metrics are computed in Java services.
   - The LLM receives structured context and cites the supplied evidence.
   - If data is missing, responses must state the gap.

2. **Adjusted prices first**
   - Return, volatility, beta, covariance, and technical calculations use `adjustedClose` where available.
   - `closePrice` is only a fallback.
   - Records flagged as `SUSPECT_GAP` are excluded from return series.

3. **Confidence before recommendation**
   - A stock can only receive a hard `BUY` when both score and confidence are high.
   - Missing fundamentals, stale macro data, thin price history, and absent news coverage reduce confidence.

4. **Cheap is not automatically good**
   - Valuation is scored with quality and growth context.
   - Cheap stocks with weak growth, poor margins, high leverage, or negative free cash flow are penalized as potential value traps.

5. **Portfolio fit is a sizing input, not a buy thesis**
   - Low correlation and diversification benefits improve `portfolioFitScore`.
   - They cannot create a `BUY` without valuation, quality, growth, and risk support.

## Main Implementation Map

| Area | Primary Classes |
|---|---|
| Return series | `ReturnSeriesService` |
| Covariance, beta, drawdown, EWMA, stress metrics | `CovarianceEngine` |
| Portfolio risk | `PortfolioRiskService`, `RiskDecomposition` |
| Factor risk model | `FactorModelService`, `FactorReturnService`, `FactorRiskReport` |
| Technicals | `TechnicalAnalysisService`, `TechnicalSnapshot` |
| Fundamentals | `FundamentalsService`, `StockFundamentals` |
| Research assembly | `StockIntelligenceService`, `StockDeepDive` |
| Scorecard | `StockScorecardService`, `StockScorecard`, `EvidenceItem` |
| Macro | `MacroStateService`, `MacroSnapshot` |
| News | `NewsAggregatorService`, `NewsClassificationPipeline`, `NewsArticle` |
| Research chat context | `CFOAdvisorService` |

## Data Inputs

### Price History

Entity: `StockPriceHistory`

Important fields:

- `symbol`
- `priceDate`
- `openPrice`
- `highPrice`
- `lowPrice`
- `closePrice`
- `adjustedClose`
- `volume`
- `priceChangePercent`
- `dataQualityFlag`
- circuit-breaker fields

Primary modelling usage:

- Return series.
- Technical indicators.
- Beta and covariance.
- Volatility and drawdown.
- Liquidity proxy when volume exists.

### Fundamentals

Entity: `StockFundamentals`

Important fields:

- Multiples: `trailingPE`, `forwardPE`, `priceToBook`, `evToEbitda`, `evToSales`, `pegRatio`.
- Profitability: `grossMargin`, `ebitdaMargin`, `operatingMargin`, `netProfitMargin`, `roe`.
- Growth: `revenueGrowth`.
- Balance sheet and cash flow: `debtToEquity`, `freeCashFlow`.
- Yield: `dividendYield`.
- Own-history valuation: `peZScore`, `evEbitdaZScore`, `valuationLabel`.
- Data quality: `dataQualityNotes`.

Primary modelling usage:

- Valuation score.
- Quality score.
- Growth score.
- Expected-return band.
- Confidence gating.

### News

Entity: `NewsArticle`

Important fields:

- `source`
- `title`
- `summary`
- `publishedDate`
- `sentiment`
- `category`
- `impactType`
- `impactHorizon`
- `sectorImpact`
- `actionabilityScore`
- `tier1Confidence`

Primary modelling usage:

- Stock-specific catalyst evidence.
- News/macro score.
- Risk flags for adverse catalysts.

News is treated as catalyst impact, not generic tone. The scorecard classifies each item into impact lines such as:

- `REVENUE`
- `MARGIN`
- `COST_OF_CAPITAL`
- `REGULATORY`
- `VALUATION_MULTIPLE`

### Macro

Entity: `MacroSnapshot`

Important fields:

- `repoRate`
- `cpiYoY`
- `usdInr`
- `indiaVix`
- `gsecYield10Y`
- `gdpGrowth`
- `fiiNetFlow`
- `diiNetFlow`
- `dataQualityNotes`

Primary modelling usage:

- Macro regime classification.
- Sector-sensitive news/macro score adjustment.
- Portfolio risk interpretation.

Current macro regimes:

- `RISK_ON`
- `RISK_OFF`
- `INFLATION_PRESSURE`
- `RATE_EASING`
- `RATE_TIGHTENING`
- `FX_STRESS`
- `GROWTH_SLOWDOWN`

## Data Provider Strategy

Finwise is structured to support replaceable market data providers.

Default/free sources:

- Yahoo chart API for adjusted prices.
- NSE corporate data subscription and official disclosures.
- BSE corporate announcements.
- RBI DBIE for rates and macro.
- MOSPI/NSO releases for CPI and GDP where available.

Paid-ready future adapters:

- Indian fundamentals APIs such as Stoxim or equivalent.
- Paid corporate-action APIs.
- Paid news, estimates, and peer-comparison providers.

Boundary:

- `MarketDataProvider`
- `MarketDataProvider.FundamentalsProvider`
- `MarketDataProvider.MarketEventsProvider`

## Canonical Return Series

Class: `ReturnSeriesService`

Return formula:

```text
r_t = P_t / P_t-1 - 1
```

Where:

- `P_t` is `adjustedClose` when available.
- `closePrice` is used only as fallback.
- `SUSPECT_GAP` records are skipped.

Minimum observation policy:

```text
MIN_OBSERVATIONS = 60
```

Symbols with fewer than 60 valid daily returns are excluded from risk calculations.

## Portfolio Risk Model

Class: `PortfolioRiskService`

Output: `RiskDecomposition`

### Holdings Included

Only active investments with:

- non-empty `symbol`
- positive `currentValue`
- sufficient price history

Weights are market-value weights:

```text
w_i = currentValue_i / totalPortfolioMarketValue
```

When some holdings are excluded due to insufficient history, included holdings are renormalized for covariance calculations.

### Covariance Matrix

Class: `CovarianceEngine`

Returns are aligned on common trading dates. The covariance matrix uses sample covariance with `N - 1` denominator:

```text
Σ_ij = cov(r_i, r_j)
```

### Portfolio Variance and Volatility

Formula:

```text
σ_p^2 = w^T Σ w
σ_p = sqrt(σ_p^2)
annualizedVolatility = σ_p * sqrt(252)
```

The service returns both:

- daily volatility
- annualized volatility

### Beta

Benchmark: Nifty 50 symbol from `StockPriceService.NIFTY_SYMBOL`.

Per-holding beta:

```text
β_i = cov(r_i, r_Nifty) / var(r_Nifty)
```

Portfolio beta:

```text
β_p = Σ w_i β_i
```

Additional beta helpers:

- Downside beta: beta using only dates where Nifty return is negative.
- Rolling beta: beta over rolling windows, such as 60d or 120d.

### VaR and CVaR

Parametric VaR:

```text
VaR95 = 1.645 * dailyVolatility * portfolioValue
VaR99 = 2.326 * dailyVolatility * portfolioValue
```

Interpretation:

- This is a normal-assumption model.
- Confidence should be lower in high-VIX or fat-tail conditions.

Historical VaR:

```text
VaR95Historical = -5thPercentile(portfolioReturns) * portfolioValue
```

CVaR / expected shortfall:

```text
CVaR95 = -average(returns worse than or equal to VaR95 return threshold) * portfolioValue
```

### Risk Contributions

Intermediate:

```text
Σw = covariance matrix multiplied by weight vector
```

Marginal contribution to risk:

```text
MCR_i = (Σw)_i / σ_p
```

Component contribution to risk:

```text
CCR_i = w_i * MCR_i
```

Percent contribution:

```text
p_i = CCR_i / σ_p
```

Risk contributors are sorted descending by percent contribution.

### Diversification Metrics

Diversification ratio:

```text
diversificationRatio = Σ(w_i * σ_i) / σ_p
```

Effective number of bets:

```text
ENB = 1 / Σ(p_i^2)
```

Name concentration:

```text
nameHHI = Σ(w_i^2)
```

Sector concentration:

```text
sectorHHI = Σ(sectorWeight_i^2)
```

### Sharpe and Sortino

Annualized return:

```text
annualizedReturn = mean(dailyPortfolioReturns) * 252
```

Sharpe:

```text
Sharpe = (annualizedReturn - riskFreeRate) / annualizedVolatility
```

Downside deviation:

```text
downsideDeviation = sqrt(mean(min(r_t, 0)^2)) * sqrt(252)
```

Sortino:

```text
Sortino = (annualizedReturn - riskFreeRate) / downsideDeviation
```

### Tracking Error

```text
trackingError = std(r_portfolio - r_Nifty) * sqrt(252)
```

### Drawdown

Class helper: `CovarianceEngine.maxDrawdown`

Formula:

```text
runningMax_t = max(P_0 ... P_t)
drawdown_t = P_t / runningMax_t - 1
maxDrawdown = min(drawdown_t)
```

Output is negative. Example: `-0.25` means a 25 percent peak-to-trough drawdown.

### EWMA Volatility

Class helper: `CovarianceEngine.ewmaVolatilityAnnualized`

Default RiskMetrics lambda:

```text
λ = 0.94
```

Formula:

```text
var_t = λ * var_t-1 + (1 - λ) * r_t^2
ewmaVolatility = sqrt(var_t) * sqrt(252)
```

### Stressed Correlation

Class helper: `CovarianceEngine.stressedCorrelation`

Stressed correlation uses the worst benchmark-return days:

```text
1. Align stock and benchmark return dates.
2. Sort common dates by benchmark return ascending.
3. Keep worst fraction, usually 20 percent.
4. Compute correlation over those dates.
```

### Liquidity Proxy

Class helper: `CovarianceEngine.averageTradedValue`

Formula:

```text
averageTradedValue = average(closePrice_t * volume_t)
```

Only overlapping price and volume dates are used.

## Factor Risk Model

Classes: `FactorModelService`, `FactorReturnService`, `FactorRiskReport`, config `FactorProperties` (`cfo.factors.*`)

An index-based multi-factor decomposition reported alongside (never merged into) the
covariance-based `RiskDecomposition`.

### Factor Construction

Daily factor returns are built from persisted NSE index history (Yahoo tickers,
fetched by the 16:00 price job over `cfo.factors.lookback-days`, default 730):

```text
MKT      = r(^NSEI)
SIZE     = r(^NSEMDCP50) - r(^NSEI)        (spread, to decollinearize)
SECTOR_k = r(sector index k) - r(^NSEI)
```

Gazetteer sectors map to sector indices via `cfo.factors.sector-index.*`
(Banking/Financial Services/Fintech → `^NSEBANK`, IT/Internet → `^CNXIT`,
Pharma/Healthcare → `^CNXPHARMA`, FMCG/Consumer/Retail → `^CNXFMCG`,
Auto → `^CNXAUTO`, Metals → `^CNXMETAL`, Energy/Power → `^CNXENERGY`).
Unmapped sectors and missing indices degrade the regression to the factors that
remain — never a failure.

### Per-Holding Regression

```text
r_i = alpha + beta_mkt * MKT + beta_size * SIZE + beta_sec * SECTOR_s(i) + eps
```

Exactly the holding's OWN sector spread enters — never all sector indices at once
(sector spreads correlate with each other; own-sector-only avoids multicollinearity).
OLS requires at least `cfo.factors.min-observations` (default 120) aligned days,
else the holding is excluded with a `FACTOR_EXCLUDED` note. Reported per holding:
betas, R², idiosyncratic vol `sigma_eps * sqrt(252)`, and t-stats. The t-stats are
HAC-naive (no Newey-West correction); only `|t| >= 2` is displayed as significant.

### Portfolio Aggregation

```text
B_k                  = sum_i w_i * beta_ik          (weights renormalized to included holdings)
systematic variance  = B' * Sigma_F * B             (Sigma_F via Ledoit-Wolf when >= 3 factors)
idiosyncratic var    = sum_i w_i^2 * sigma_eps_i^2
systematic share     = systematic / (systematic + idiosyncratic)
```

Per-factor contribution to systematic variance is `B_k * (Sigma_F B)_k / B' Sigma_F B`
(shares sum to 100 percent; an individual share can be slightly negative through
covariance cross-terms — that is informative and kept).

The model total is reported NEXT TO the direct `w' Sigma w` portfolio volatility over
the same window. The gap between the two is estimation noise; they are never forced
to reconcile.

### Data Quality

`SIZE_FACTOR_UNAVAILABLE`, `SECTOR_FACTOR_MISSING`, `FACTOR_EXCLUDED`,
`EXCLUDED_WEIGHT`, and `FACTOR_COV_UNAVAILABLE` notes mirror the risk-engine
conventions; excluded weight above 25 percent or any missing factor forces
LOW_CONFIDENCE.

## Technical Analysis Model

Class: `TechnicalAnalysisService`

Output: `TechnicalSnapshot`

Technical indicators include:

- SMA20, SMA50, SMA200.
- EMA20, EMA50, EMA200.
- Price versus moving averages.
- RSI14 using Wilder smoothing.
- ATR14 using Wilder smoothing.
- 20-day realized volatility annualized.
- 52-week high and low.
- 5-day and 20-day returns.
- Golden cross state.
- MACD 12/26/9.
- Trend label: `UP`, `DOWN`, `SIDEWAYS`.
- Momentum label: `OVERBOUGHT`, `NEUTRAL`, `OVERSOLD`.

Important rule:

- High RSI alone must not create an `AVOID` recommendation.
- RSI is an input to momentum and risk interpretation, not a complete thesis.

## Stock Research Assembly

Class: `StockIntelligenceService`

Output: `StockDeepDive`

The service assembles:

- latest quote
- technical snapshot
- fundamentals
- relevant stock news
- macro snapshot
- portfolio fit
- scorecard
- data gaps

Research queries always return a `StockDeepDive`, even when data is missing. Missing data is represented through `dataGaps`, not exceptions.

Critical guardrail:

- If no price history exists, `hasPriceHistory=false` and `DATA_INCOMPLETE:PRICE_HISTORY` is surfaced.
- The research prompt explicitly forbids the LLM from using training-data recall to fill the missing live data.

## Portfolio Fit for Candidate Stocks

Class: `StockIntelligenceService`

Candidate portfolio fit includes:

- whether the stock is currently held
- current portfolio weight if held
- beta versus Nifty
- percent contribution to risk if held
- sector
- correlation to portfolio
- marginal annualized volatility impact
- projected portfolio volatility after hypothetical addition
- default hypothetical position weight of 5 percent

Marginal-volatility model:

```text
newPortfolioReturn_t = (1 - h) * existingPortfolioReturn_t + h * candidateReturn_t
```

Where:

```text
h = hypothetical candidate weight, currently 0.05
```

The model computes:

```text
existingVolAnnualized
newVolAnnualized
deltaVol = newVolAnnualized - existingVolAnnualized
correlation(candidate, existingPortfolio)
```

## Stock Scorecard Model

Class: `StockScorecardService`

Output: `StockScorecard`

The scorecard is the authoritative stock recommendation object for research mode.

### Components

Each component is scored from `0` to `100`:

- `valuationScore`
- `qualityScore`
- `growthScore`
- `momentumScore`
- `riskScore`
- `newsMacroScore`
- `portfolioFitScore`
- `confidence`

### Weighted Total Score

Formula:

```text
totalScore =
  0.20 * valuationScore
+ 0.20 * qualityScore
+ 0.15 * growthScore
+ 0.15 * momentumScore
+ 0.15 * riskScore
+ 0.10 * newsMacroScore
+ 0.05 * portfolioFitScore
```

### Recommendation Labels

The scorecard emits one of:

- `BUY`
- `WAIT`
- `AVOID`
- `NEEDS_MORE_DATA`

Decision gates:

```text
if confidence < 50:
    NEEDS_MORE_DATA
else if severe risk, valuation, or news/macro risk:
    AVOID
else if totalScore >= 70 and confidence >= 70:
    BUY
else if totalScore < 45:
    AVOID
else:
    WAIT
```

### Confidence Model

Confidence starts at `100` and is reduced for:

- missing adjusted prices
- fewer than 252 trading observations
- fewer than 120 price observations
- missing fundamentals
- stale or missing macro
- no recent news coverage
- missing peer valuation
- unresolved corporate-action anomalies

Hard caps:

```text
fundamentals unavailable -> confidence <= 60
price observations < 120 -> confidence <= 50
```

If confidence is below 50, the LLM must refuse a hard recommendation and explain missing data.

## Valuation Model

Valuation is sector-aware.

### Banks, NBFCs, Financials

Preferred metrics:

- P/B
- ROE
- profitability
- asset-quality fields when available in future feeds

Current v1 scoring:

- Lower P/B is better, but only with quality context.
- ROE supports valuation when available.
- Missing P/B is a data gap for financial-sector stocks.

### Industrials, IT, FMCG, Pharma, and Other Non-Financials

Preferred metrics:

- trailing P/E
- EV/EBITDA
- EV/Sales
- margins
- growth context

Current v1 scoring:

- Lower P/E and EV/EBITDA improve valuation score.
- Very high multiples reduce valuation score.
- Own-history P/E z-score is used as fallback when peer data is unavailable.

### Own-History Z-Score

Used when normalized peer percentile data is unavailable:

```text
z = (currentMultiple - historicalMean) / historicalStdDev
```

Interpretation:

- low z-score: cheaper versus own history
- high z-score: expensive versus own history

### Value-Trap Penalty

A cheap stock is penalized if cheapness is paired with any of:

- negative revenue growth
- weak or negative margins
- high debt-to-equity
- negative free cash flow

This prevents a deteriorating business from becoming `BUY` merely because it is statistically cheap.

## Quality Model

Quality scoring uses:

- profitability flag
- ROE
- operating margin
- debt-to-equity
- free cash flow

High-quality evidence improves the score when:

- the company is profitable
- ROE is strong for its sector
- operating margins are healthy
- leverage is manageable
- free cash flow is positive

Missing ROE or margin fields are surfaced as data gaps.

## Growth Model

Growth scoring uses:

- revenue growth
- net margin
- PEG ratio when available

Interpretation:

- strong positive revenue growth improves score
- negative growth reduces score
- margin support improves confidence in the growth score

## Expected-Return Evidence

The app does not produce fake precision such as exact target prices.

Instead, it creates a qualitative expected-return band:

- `Attractive`
- `Fair`
- `Poor`

Inputs:

- valuation score
- quality score
- growth score
- earnings yield
- dividend yield as v1 shareholder yield

Earnings yield:

```text
earningsYield = 1 / trailingPE
```

In rendered evidence this is shown as:

```text
100 / trailingPE percent
```

Shareholder yield v1:

```text
shareholderYield = dividendYield
```

No target price is implied.

## Momentum Model

Momentum scoring uses:

- trend label
- RSI14
- 20-day return
- golden cross

Rules:

- Uptrend improves score.
- Downtrend reduces score.
- RSI above 70 reduces momentum modestly but does not cause `AVOID`.
- Positive 20-day return improves score.

## Risk Score

Risk scoring uses:

- beta versus Nifty
- 20-day realized volatility
- marginal volatility impact from portfolio fit

Interpretation:

- lower beta generally improves score
- very high beta reduces score
- lower realized volatility improves score
- candidate positions that materially increase portfolio volatility reduce score

## News and Macro Score

News and macro are combined in `newsMacroScore`.

### News Decay

News items decay by age:

- same-day items receive full weight
- short/medium-term items decay with age
- 7-day events are below 40 percent weight unless structural
- long-term structural items retain more weight

### Source Reliability

Source reliability weighting:

- official sources such as NSE, BSE, RBI, SEBI: highest reliability
- exchange or official disclosures: high reliability
- ordinary media/news feeds: lower reliability

### Macro Regime

Macro regimes are inferred from:

- India VIX
- CPI
- USD/INR
- GDP growth
- repo rate

The macro adjustment is sector-sensitive. For example:

- `RATE_TIGHTENING` can be mildly constructive for banks but negative for rate-sensitive sectors.
- `FX_STRESS` can be positive for IT/pharma exporters but negative for import-heavy sectors.
- `RISK_OFF` is broadly negative.

## Evidence Items

Model: `EvidenceItem`

Each scorecard can include evidence items with:

- `category`
- `metricName`
- `value`
- `interpretation`
- `direction`
- `confidence`
- `source`
- `asOf`

Example categories:

- `VALUATION`
- `QUALITY`
- `GROWTH`
- `MOMENTUM`
- `RISK`
- `NEWS`
- `MACRO`
- `PORTFOLIO_FIT`
- `EXPECTED_RETURN`

The LLM should cite these evidence items directly in stock research responses.

## Research Chat Behaviour

Class: `CFOAdvisorService`

When intent is classified as stock research:

1. `StockIntelligenceService.analyze(symbol, userId)` builds the deep dive.
2. `StockScorecard` is placed first in the prompt context.
3. The LLM is instructed to use the scorecard recommendation exactly.
4. The LLM must cite component scores and evidence before narrative.
5. If confidence is below 50, the response must explain missing data and avoid a hard call.

Research response structure:

1. Quick verdict.
2. Scorecard first.
3. Data evidence.
4. Thesis.
5. Break condition.
6. Fit to portfolio.
7. Concrete action.

## Data Quality and Gaps

Data gaps are first-class modelling outputs.

Common examples:

- `DATA_INCOMPLETE:PRICE_HISTORY`
- `DATA_INCOMPLETE:TECHNICALS`
- `DATA_INCOMPLETE:FUNDAMENTALS`
- `DATA_INCOMPLETE:MACRO`
- `DATA_INCOMPLETE:BETA`
- `DATA_INCOMPLETE:MARGINAL_VOL`
- `DATA_INCOMPLETE:NEWS`
- `DATA_INCOMPLETE:PEER_VALUATION`
- `DATA_INCOMPLETE:VALUATION`
- `DATA_INCOMPLETE:QUALITY`
- `DATA_INCOMPLETE:GROWTH`

The LLM must not hide these gaps. Missing data should reduce confidence and be mentioned in the output.

## Test Coverage

Current important tests:

- `ReturnSeriesServiceTest`
- `TechnicalAnalysisServiceTest`
- `CovarianceEngineTest`
- `FundamentalsServiceTest`
- `StockScorecardServiceTest`
- `StockIntelligenceServiceGoldenTest`

### Formula Tests

`CovarianceEngineTest` covers:

- covariance matrix
- beta
- marginal volatility impact
- EWMA volatility
- downside beta
- rolling beta
- max drawdown
- stressed correlation
- average traded value liquidity proxy

### Scorecard Golden Scenarios

`StockScorecardServiceTest` covers:

- `BUY` requires high score and high confidence.
- Missing fundamentals and thin price history force low confidence.
- Expensive high-quality compounders are not automatically `AVOID`.
- Cheap deteriorating businesses are not automatically `BUY`.
- Low-correlation stocks cannot become `BUY` on portfolio fit alone.
- High RSI alone does not produce `AVOID`.

### Research Safety Regression

`StockIntelligenceServiceGoldenTest` verifies:

- unknown symbols with no price history return a deep dive
- missing price data is surfaced as `DATA_INCOMPLETE:PRICE_HISTORY`
- live data gaps are not silently hidden

## Running Tests

The project targets Java 21. If the default shell Java is older, use an installed JDK 21+.

On the current development machine, JDK 25 is available:

```bash
JAVA_HOME=/Users/amittiwari/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home \
./mvnw test -Dtest=CovarianceEngineTest,StockScorecardServiceTest,StockIntelligenceServiceGoldenTest
```

Expected targeted result:

```text
Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full suite:

```bash
JAVA_HOME=/Users/amittiwari/Library/Java/JavaVirtualMachines/openjdk-25.0.1/Contents/Home ./mvnw test
```

Known caveat:

- The full suite may fail in `FinwiseApplicationTests` if PostgreSQL is unavailable or sandboxed, because that test loads the full Spring context and opens a DB connection.

## Current Limitations

1. **Peer valuation is not fully implemented**
   - The scorecard is ready for peer percentiles, but current v1 mostly uses available fundamentals and own-history z-scores.

2. **Fundamentals are only as good as the current feed**
   - Yahoo-derived fundamentals are convenient but lower trust than exchange filings or paid normalized datasets.

3. **Asset-quality metrics for banks are not yet modelled**
   - Fields such as GNPA, NNPA, credit cost, provision coverage, and NIM should be added when the data provider supports them.

4. **Macro is rule-based**
   - Regime classification is deterministic and simple. It should be expanded with proper time-series freshness and directional changes.

5. **News impact is approximate**
   - v1 uses available classification fields and source reliability. A richer event ontology would improve precision.

6. **No exact target prices**
   - This is intentional. The system reports qualitative expected-return bands rather than false-precision price targets.

7. **Liquidity proxy is available as a helper**
   - It is implemented from price and volume, but not yet deeply integrated into every scorecard decision.

## Future Improvements

High-value next steps:

- Add normalized peer groups and peer percentile valuation.
- Add bank-specific fundamentals: NIM, GNPA, NNPA, CASA, credit cost.
- Add corporate action reconciliation and anomaly confidence caps.
- Add explicit max drawdown, downside beta, EWMA volatility, stressed correlation, and liquidity fields to `StockDeepDive`.
- Persist scorecards for auditability and historical recommendation tracking.
- Add macro freshness checks for each field rather than one generic data-quality note.
- Add paid provider implementations behind `MarketDataProvider`.
- Add integration tests with price, fundamentals, news, macro, and portfolio holdings together.

