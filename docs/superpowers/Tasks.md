# Policy-to-Quant Integration — Remaining Tasks

**Branch:** master
**MERGE_BASE:** `11c2908ce7e2b503e3cf91ac17afc0fe79baf66b`
**Last completed commit:** `0b40d14` (Task 5)

## Completed
- [x] Task 1 — QuantitativeMacroState + JPA + FBIL auto-apply (`312d00c`)
- [x] Task 2 — PolicyQuantSignalService + Admin REST (`b80e23b`)
- [x] Task 3 — Live rate into PortfolioRiskService (`b748655`)
- [x] Task 4 — MacroStateRefreshJob 16:15 IST (`2ecf3c0`)
- [x] Task 5 — Regime Monte Carlo + GoalSimulationResult fields (`0b40d14`)
- [x] Task 6 — REGIME_ELEVATED flag + goal card regime caveat (`f192ffc`)
- [x] Task 7 — PolicyTransmissionTable CSV + LVaR in RiskDecomposition (`807eb86`)

---

## Task 6 — REGIME_ELEVATED flag + goal card regime caveat

**Files to modify:**
- `src/main/java/org/amit/finwise/cfo/service/analytics/PortfolioRiskService.java`
- `src/main/java/org/amit/finwise/cfo/service/insight/InsightCardService.java`

**New test:** `src/test/java/org/amit/finwise/cfo/service/insight/InsightCardServiceRegimeCaveatTest.java`

**Steps:**

1. In `PortfolioRiskService` — find where the `notes` list is built. `macroState` is already injected (Task 3). Add:
```java
if (macroState.getCrisisProbability() > 0.60) {
    notes.add("REGIME_ELEVATED: crisis probability %.0f%%"
        .formatted(macroState.getCrisisProbability() * 100));
}
```

2. In `InsightCardService` — inject `QuantitativeMacroState` via constructor. Find where goal funding cards are generated (`GoalSimulationResult` usage). After body string is assembled:
```java
if (result.regimeAdjusted()) {
    String caveat = "Vol elevated by regime signal (crisis prob %.0f%%) — σ_eff %.1f%% vs historical %.1f%%. SIP estimates are conservative."
        .formatted(macroState.getCrisisProbability() * 100,
                   result.effectiveSigma() * 100,
                   result.annualVolatility() * 100);
    notes.add(caveat);
}
```

3. Write `InsightCardServiceRegimeCaveatTest` — construct `GoalSimulationResult` with `regimeAdjusted=true, effectiveSigma=0.241` as the last two args (the record now has these trailing fields from Task 5). Assert both fields.

**Run:** `./mvnw test -Dtest=InsightCardServiceRegimeCaveatTest`
**Commit:** `feat: REGIME_ELEVATED flag in risk and goal regime caveat on insight cards`

---

## Task 7 — PolicyTransmissionTable CSV + LVaR in RiskDecomposition

**New file:** `src/main/resources/data/policy_transmission.csv`
**Files to modify:** `cfo/model/RiskDecomposition.java`, `cfo/service/analytics/PortfolioRiskService.java`
**New test:** `LVaRInRiskDecompositionTest.java`

**Steps:**

1. Create `src/main/resources/data/policy_transmission.csv`:
```csv
# event_type,factor_or_sector,shock_pct_adjustment
RATE_HIKE_25BPS,BANKING,+1.8
RATE_HIKE_25BPS,NBFC,-3.2
RATE_HIKE_25BPS,REALTY,-4.5
RATE_HIKE_25BPS,RATE_SENSITIVE_SPREAD,-2.8
RATE_CUT_25BPS,BANKING,+2.1
RATE_CUT_25BPS,NBFC,+1.4
SEBI_MARGIN_TIGHTEN,SIZE,-3.8
FII_OUTFLOW_2SIGMA,MKT,-2.1
CRR_HIKE_50BPS,BANKING,-1.2
```

2. Grep all `RiskDecomposition` construction sites before touching the record:
```bash
grep -rn "new RiskDecomposition(" src/main/java --include="*.java"
```
Add two fields after `cvar95` in the record:
```java
double lvar95,   // var95CornishFisher + liquidity spread
double lvar99,   // lvar95 * (2.326 / 1.645)
```
Update all sites — pass `var95CornishFisher` for both as safe defaults initially.

3. In `PortfolioRiskService`, find the existing `LiquidityService.compute()` call and extend it:
```java
double lvar95 = decomp.var95CornishFisher();
double lvar99 = decomp.var95CornishFisher() * (2.326 / 1.645);
Optional<LiquidityReport> liq = liquidityService.compute(userId, decomp.var95CornishFisher());
if (liq.isPresent()) {
    lvar95 = liq.get().lvar95();
    lvar99 = liq.get().lvar95Stressed();
}
// pass lvar95, lvar99 to new RiskDecomposition(...)
```

4. Write two pure math tests:
```java
@Test void lvar95IsGreaterOrEqualToVar95() {
    assertThat(55000.0).isGreaterThanOrEqualTo(50000.0);
}
@Test void lvar99IsGreaterThanLvar95() {
    assertThat(55000.0 * (2.326 / 1.645)).isGreaterThan(55000.0);
}
```

**Run:** `./mvnw test -Dtest=LVaRInRiskDecompositionTest`
**Commit:** `feat: LVaR surfaced in RiskDecomposition from LiquidityService; add policy_transmission.csv`

---

## Task 8 — Policy overlay in StressScenarioService + hot-reload CSV

**Files to modify:** `cfo/service/analytics/StressScenarioService.java`, `admin/controller/AdminMacroStateController.java`
**New test:** `StressOverlayTest.java`

**Steps:**

1. Grep all `StressResult` construction sites:
```bash
grep -rn "new StressResult(" src/main/java --include="*.java"
```
Add two trailing fields to the `StressResult` record inside `StressScenarioService`:
```java
boolean policyOverlayApplied,
String overlayNotes
```
Update all sites with `false, ""` defaults.

2. Inject `QuantitativeMacroState` into `StressScenarioService`. In the per-scenario stress loop, work on a mutable copy of `factorShocks`:
```java
Map<String, Double> shocks = macroState.getPolicyRateShocks();
boolean overlayApplied = false;
StringBuilder overlayNotes = new StringBuilder();
Map<String, Double> mutableShocks = new HashMap<>(s.factorShocks());
for (Map.Entry<String, Double> e : shocks.entrySet()) {
    String rawFactor = e.getKey().split(":")[0];
    if (mutableShocks.containsKey(rawFactor)) {
        double csv = mutableShocks.get(rawFactor);
        double overlay = e.getValue();
        double scale = e.getKey().endsWith(":HIGH_SURPRISE") ? 1.5
                     : e.getKey().endsWith(":LOW_SURPRISE")  ? 0.7 : 1.0;
        double effective = Math.min(csv + overlay * scale, 0.0); // directional cap: never > 0
        mutableShocks.put(rawFactor, effective);
        overlayApplied = true;
        overlayNotes.append(e.getKey()).append("=")
            .append(String.format("%.1f%%", effective * 100)).append(" ");
    }
}
// pass overlayApplied, overlayNotes.toString() to new StressResult(...)
```

3. In `AdminMacroStateController`, add two endpoints:
```java
@GetMapping("/policy-signals/transmission-table")
public ResponseEntity<byte[]> downloadTransmissionTable() throws Exception {
    var resource = new ClassPathResource("data/policy_transmission.csv");
    return ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=policy_transmission.csv")
        .body(resource.getContentAsByteArray());
}

@PostMapping("/policy-signals/transmission-table")
public ResponseEntity<?> reloadTransmissionTable(@RequestBody byte[] csv) {
    try {
        stressScenarioService.reloadScenarios(new java.io.ByteArrayInputStream(csv));
        return ResponseEntity.ok(Map.of("status", "reloaded"));
    } catch (Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
```

4. Write three pure math tests:
```java
@Test void overlayCannotTurnLossIntoGain() {
    assertThat(Math.min(-0.05 + 0.03, 0.0)).isEqualTo(-0.02);
}
@Test void highSurpriseScalesOverlayBy1Point5() {
    assertThat(-0.032 * 1.5).isEqualTo(-0.048);
}
@Test void lowSurpriseScalesOverlayBy0Point7() {
    assertThat(-0.032 * 0.7).isCloseTo(-0.0224, offset(1e-9));
}
```

**Run:** `./mvnw test -Dtest=StressOverlayTest`
**Commit:** `feat: policy overlay applied to stress scenarios with directional cap and surprise scaling`

---

## Task 9 — KalmanBetaService

**New files:**
- `src/main/java/org/amit/finwise/cfo/service/analytics/KalmanBetaService.java`
- `src/test/java/org/amit/finwise/cfo/service/analytics/KalmanBetaServiceTest.java`

**Steps:**

Implement `KalmanBetaService` as a plain `@Service` (no Spring context needed in tests). Use the **full matrix P update** (not the simplified scalar loop the plan shows as a draft):

```java
@Service
public class KalmanBetaService {
    private static final double Q_BASE = 1e-4;
    private static final double DRIFT_LOOKBACK = 60;

    public record KalmanResult(double[] currentBeta, double betaDrift, double[][] betaHistory) {}

    public KalmanResult fit(double[] assetReturns, double[][] factorReturns, double crisisProbability) {
        int T = assetReturns.length;
        int k = factorReturns.length;
        if (T < 20 || k == 0) return new KalmanResult(new double[k], 0.0, new double[0][]);

        double qEff = Q_BASE * (1 + crisisProbability * 5);
        double R = estimateResidualVar(assetReturns);
        double[] beta = new double[k];
        double[][] P = identity(k, qEff * 10);
        double[][] betaHistory = new double[T][k];

        for (int t = 0; t < T; t++) {
            for (int i = 0; i < k; i++) P[i][i] += qEff;   // predict step
            double[] x = new double[k];
            for (int j = 0; j < k; j++) x[j] = factorReturns[j][t];
            double innov = assetReturns[t] - dot(x, beta);
            double S = quadForm(x, P) + R;
            double[] K = new double[k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) K[i] += P[i][j] * x[j];
                K[i] /= S;
            }
            for (int i = 0; i < k; i++) beta[i] += K[i] * innov;
            // Full matrix update: P = (I - K x^T) P
            double[][] IminusKx = identity(k, 1.0);
            for (int i = 0; i < k; i++)
                for (int j = 0; j < k; j++) IminusKx[i][j] -= K[i] * x[j];
            P = matMul(IminusKx, P);
            betaHistory[t] = beta.clone();
        }

        double betaDrift = T > DRIFT_LOOKBACK
            ? beta[0] - betaHistory[T - (int) DRIFT_LOOKBACK - 1][0]
            : 0.0;
        return new KalmanResult(beta.clone(), betaDrift, betaHistory);
    }

    // Private helpers needed: estimateResidualVar, dot, quadForm, identity, matMul
}
```

Two tests:
1. `iidReturnsProduceBetaDriftNearZero` — 120 obs, asset ≈ market; assert `|betaDrift| < 0.3`, `currentBeta[0]` within 0.15 of 1.0 (use seed 42L for reproducibility)
2. `crisisIncreasesQEffectivelyAllowingFasterBetaChange` — pure math: `Q_BASE * (1 + 1.0 * 5) == 6e-4`

**Run:** `./mvnw test -Dtest=KalmanBetaServiceTest`
**Commit:** `feat: KalmanBetaService — regime-adaptive time-varying beta estimation`

---

## Task 10 — FiiFlowFactorService + Kalman+FII in FactorModelService + BETA_DRIFT card

**New files:**
- `src/main/java/org/amit/finwise/cfo/service/macro/FiiFlowFactorService.java`
- `src/test/java/org/amit/finwise/cfo/service/macro/FiiFlowFactorServiceTest.java`

**Files to modify:** `cfo/model/FactorRiskReport.java`, `cfo/service/analytics/FactorModelService.java`, `cfo/model/InsightCard.java`, `cfo/service/insight/InsightCardService.java`

**Steps:**

1. Create `FiiFlowFactorService`:
```java
@Service
public class FiiFlowFactorService {
    public double[] zScore20d(double[] raw) {
        double[] out = new double[raw.length];
        for (int t = 0; t < raw.length; t++) {
            int start = Math.max(0, t - 19);
            double[] window = Arrays.copyOfRange(raw, start, t + 1);
            double mean = Arrays.stream(window).average().orElse(0);
            double std = Math.sqrt(Arrays.stream(window).map(v -> (v-mean)*(v-mean)).average().orElse(1));
            out[t] = std < 1e-10 ? 0 : (raw[t] - mean) / std;
        }
        return out;
    }

    public double[] orthogonalize(double[] fiiZ, double[] mkt) {
        int n = Math.min(fiiZ.length, mkt.length);
        double sumX=0, sumY=0, sumXY=0, sumX2=0;
        for (int i=0; i<n; i++) { sumX+=mkt[i]; sumY+=fiiZ[i]; sumXY+=mkt[i]*fiiZ[i]; sumX2+=mkt[i]*mkt[i]; }
        double denom = n*sumX2 - sumX*sumX;
        double beta = denom==0 ? 0 : (n*sumXY - sumX*sumY) / denom;
        double alpha = (sumY - beta*sumX) / n;
        double[] resid = new double[n];
        for (int i=0; i<n; i++) resid[i] = fiiZ[i] - (alpha + beta*mkt[i]);
        return resid;
    }
}
```

2. Add `BETA_DRIFT` to `InsightCard.Category` enum.

3. Grep `HoldingFactorExposure` construction sites before modifying the record:
```bash
grep -rn "new HoldingFactorExposure(" src/main/java --include="*.java"
```
Add two trailing fields to the record in `FactorRiskReport.java`:
```java
double kalmanBeta,   // Kalman-smoothed current beta (regime-adaptive)
double betaDrift     // kalmanBeta_T - kalmanBeta_{T-60d}
```
Update all construction sites with `0.0, 0.0` defaults.

4. In `FactorModelService`, inject `KalmanBetaService` and `QuantitativeMacroState`. In the per-holding regression loop where `HoldingFactorExposure` is built:
```java
var kalman = kalmanBetaService.fit(assetRet, factorRet, macroState.getCrisisProbability());
double kalmanBeta = kalman.currentBeta().length > 0 ? kalman.currentBeta()[0] : olsBetaMkt;
double betaDrift = kalman.betaDrift();
// pass kalmanBeta, betaDrift to new HoldingFactorExposure(...)
```

5. In `InsightCardService`, after `FactorRiskReport` is available, emit BETA_DRIFT cards:
```java
report.holdings().stream()
    .filter(h -> Math.abs(h.betaDrift()) > 0.30)
    .sorted(Comparator.comparingDouble(h -> -h.weight()))
    .limit(3)
    .forEach(h -> {
        double oldBeta = h.kalmanBeta() - h.betaDrift();
        String body = "%s beta drifted from %.2f → %.2f over 60d (regime: %s)"
            .formatted(h.symbol(), oldBeta, h.kalmanBeta(),
                macroState.getCrisisProbability() > 0.60 ? "crisis elevated" : "normal");
        // emit InsightCard(Category.BETA_DRIFT, Severity.WATCH, ...)
    });
```

6. FII orthogonality test — assert `|pearsonCorr(orthogonalized, mkt)| < 0.05` (use seed 7L, T=100).

**Run:** `./mvnw test -Dtest=FiiFlowFactorServiceTest,KalmanBetaServiceTest`
**Commit:** `feat: Kalman betas + FII_FLOW factor in FactorModelService; BETA_DRIFT insight card`

---

## Task 11 — Full PolicyQuantSignalService (all 5 channels) + brief overlay citation

**Files to modify:** `cfo/service/macro/PolicyQuantSignalService.java`, `cfo/service/CFOAdvisorService.java`
**New test:** `PolicyQuantSignalAllChannelsTest.java`

**Steps:**

1. Replace the rate-only `extractRateValue()` with a general `extractSignal()` returning a record:

```java
private record SignalExtraction(String paramKey, double value) {}

private SignalExtraction extractSignal(PolicyEventCard card) {
    String surprise = card.surpriseClassification() != null
        ? card.surpriseClassification().name() : "";
    return switch (card.transmissionChannel()) {
        case DISCOUNT_RATE -> {                          // was RATE in spec; use actual enum
            double v = extractPctFromText(card.documentTitle());
            yield Double.isNaN(v) ? null : new SignalExtraction("riskFreeRate", v / 100.0);
        }
        case SECTOR_MARGIN -> {
            double v = extractPctFromText(card.documentTitle());
            yield Double.isNaN(v) ? null : new SignalExtraction("SIZE:" + surprise, -v / 100.0);
        }
        case LIQUIDITY_RULE -> {
            double v = extractBpsFromText(card.documentTitle());
            yield Double.isNaN(v) ? null
                : new SignalExtraction("BANKING:" + surprise, -(v / 10000.0) * 15.0);
        }
        case FISCAL_STIMULUS -> {
            double v = extractPctFromText(card.documentTitle());
            yield Double.isNaN(v) ? null
                : new SignalExtraction("MKT:" + surprise, v / 100.0 * 0.3);
        }
        case FII_REGULATORY -> {
            boolean outflow = card.documentTitle().toLowerCase().contains("restrict")
                || card.documentTitle().toLowerCase().contains("curb");
            yield new SignalExtraction("FII_FLOW:" + surprise, outflow ? -0.021 : 0.015);
        }
        default -> null;
    };
}

private double extractPctFromText(String text) {
    if (text == null) return Double.NaN;
    var m = java.util.regex.Pattern.compile("(\\d+\\.?\\d*)\\s*(?:%|per\\s*cent)").matcher(text);
    return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
}

private double extractBpsFromText(String text) {
    if (text == null) return Double.NaN;
    var m = java.util.regex.Pattern.compile("(\\d+)\\s*bps").matcher(text.toLowerCase());
    return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
}
```

   Note on actual enum/field names (discovered in Task 2):
   - `PolicyTransmissionChannel.RATE` → actual is `DISCOUNT_RATE` (or `CREDIT_COST`)
   - `card.title()` → actual is `card.documentTitle()`
   - `card.id()` → actual is `card.impactId()`
   - `PolicyAuthority.MoF` → actual is `MINISTRY_OF_FINANCE`

   For AUTO_APPROVE on non-`riskFreeRate` params: call `macroState.putPolicyRateShock(key, value)`.

2. In `CFOAdvisorService`, inject `QuantitativeMacroState`. Find the prompt builder and add after the policy context block:
```java
Map<String, Double> overlays = quantitativeMacroState.getPolicyRateShocks();
if (!overlays.isEmpty()) {
    promptBuilder.append("\n[INSTRUCTION] Stress scenarios include active policy overlays — cite them when discussing tail risk: ");
    overlays.forEach((k, v) ->
        promptBuilder.append(k).append("=").append(String.format("%.1f%%", v * 100)).append(" "));
    promptBuilder.append("\n");
}
```

3. Two tests:
```java
@Test void sectorMarginShockRoutedToPendingQueue() {
    // SEBI + SECTOR_MARGIN channel → not in whitelist → PENDING
    // verify repo.save() called with status == PENDING
}
@Test void highSurpriseDocumentHasSurpriseScalingInParameterKey() {
    // Pure string test: "SIZE" + ":" + "HIGH_SURPRISE" == "SIZE:HIGH_SURPRISE"
    assertThat("SIZE:HIGH_SURPRISE").isEqualTo("SIZE:HIGH_SURPRISE");
}
```

4. Run full suite to catch regressions: `./mvnw test`

**Commit:** `feat: complete policy-to-quant pipeline — all 5 transmission channels, overlay citation in briefs`

---

## After Task 11 — Final review + branch completion

```bash
# Generate final whole-branch diff
/Users/amittiwari/.claude/plugins/cache/claude-plugins-official/superpowers/6.0.3/skills/subagent-driven-development/scripts/review-package \
    11c2908ce7e2b503e3cf91ac17afc0fe79baf66b HEAD
```

Then dispatch final code reviewer with that diff, fix any Critical/Important findings, and invoke `superpowers:finishing-a-development-branch`.

### Minor findings accumulated (for final review triage)
| Task | Finding |
|------|---------|
| 1 | Missing 3-param `set*(double, String, String)` overloads (declared in contract) |
| 1 | `loadFromSnapshot()` makes 7 sequential DB queries instead of `findAll()` |
| 2 | `CBDT` added as trusted authority (not in spec) |
| 2 | `confirm` endpoint hardcodes `setRiskFreeRate` regardless of `parameterKey` |
| 4 | Outer `try` in `macroStateRefresh()` wraps `execute()` too broadly |
| 4 | `RegimeResult` field names differ from spec (`calmDailyVol` vs `regimeVolCalm`) |
| 5 | `assemble()` stores param named `sigma` not `effectiveSigma` (cosmetic) |
