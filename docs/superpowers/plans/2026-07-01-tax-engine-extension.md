# Tax Engine Extension (7 Asset Types) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `CapitalGainsTaxService` to compute Indian tax for PPF, FIXED_DEPOSIT, POST_OFFICE_SCHEME, INSURANCE_POLICY, GOLD, BOND, and COMMODITY — the 7 of 9 currently-excluded `InvestmentType` values in scope for this spec (CRYPTOCURRENCY and REAL_ESTATE are deferred).

**Architecture:** Extend the existing `Regime` enum from 3 to 6 values (`EQUITY`, `DEBT_SLAB`, `INTEREST_INCOME`, `NON_EQUITY_FLAT`, `EXEMPT`, `EXCLUDED`) and route each new `InvestmentType` to the correct regime inside the existing `regimeOf()` classifier. Add 4 nullable columns to `Investment` to carry the data these regimes need (interest rate, maturity date, sum assured, annual premium). No new services, no new top-level modules — this is entirely additive work inside `investment/`.

**Tech Stack:** Spring Boot 3 / Java 21, JPA/Hibernate (`ddl-auto=update`, no manual migrations), JUnit 5 + Mockito.

## Global Constraints

- All schema changes are additive, nullable columns only — Hibernate `ddl-auto=update` handles them automatically.
- `TaxEstimate` and `RealizedTaxSummary` changes are purely additive (new fields appended) — no existing field, accessor, or the `estimate()`/`realizedTax()` method signatures may change in a way that breaks `InvestmentService` or `TaxHarvestingService` (the only two production callers).
- Missing/incomplete data never throws — it degrades to a `notes` entry describing the assumption made, and the holding still appears in the output (never silently dropped). This matches the existing `GRANDFATHERING_SKIPPED` / `MF_ASSUMED_EQUITY` convention.
- New tax rates/thresholds are admin-configurable via `@Value`-injected `cfo.tax.*` properties in `application-dev.properties`, matching the existing `cfo.tax.stcg-rate` / `cfo.tax.ltcg-rate` pattern — never hardcoded as Java literals.
- GOLD/BOND/COMMODITY get **no** Cost-Inflation-Index or indexation-choice logic — per the corrected spec, that carve-out is real-estate-only under the August 2024 Finance Act amendment. LTCG here is a flat configurable rate.
- CRYPTOCURRENCY, REAL_ESTATE, and OTHER remain `Regime.EXCLUDED` — out of scope for this plan.

---

### Task 1: Schema — new `Investment` fields, DTOs, and creation path

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/model/Investment.java`
- Modify: `src/main/java/org/amit/finwise/investment/dto/AddInvestmentRequest.java`
- Modify: `src/main/java/org/amit/finwise/investment/dto/InvestmentResponse.java`
- Modify: `src/main/java/org/amit/finwise/investment/service/InvestmentService.java:49-62`
- Modify: `src/main/java/org/amit/finwise/investment/controller/InvestmentController.java:28-36`
- Test: `src/test/java/org/amit/finwise/investment/service/InvestmentServiceTest.java` (new file)

**Interfaces:**
- Produces: `Investment.getInterestRate(): BigDecimal`, `.getMaturityDate(): LocalDate`, `.getSumAssured(): BigDecimal`, `.getAnnualPremium(): BigDecimal` (all nullable) — consumed by Task 2 onward.
- Produces: `InvestmentService.addInvestment(String, InvestmentType, String, String, LocalDate, BigDecimal, BigDecimal, String, BigDecimal, BigDecimal, BigDecimal, BigDecimal): Investment` (4 new trailing params: interestRate, maturityDate, sumAssured, annualPremium).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/amit/finwise/investment/service/InvestmentServiceTest.java`:

```java
package org.amit.finwise.investment.service;

import org.amit.finwise.cfo.service.analytics.PortfolioRiskService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.investment.repository.PortfolioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestmentServiceTest {

    @Mock InvestmentRepository investmentRepository;
    @Mock PortfolioRepository portfolioRepository;
    @Mock PortfolioRiskService portfolioRiskService;
    @Mock CapitalGainsTaxService capitalGainsTaxService;
    @Mock BondAnalyticsService bondAnalyticsService;

    private InvestmentService service;

    @BeforeEach
    void setUp() {
        service = new InvestmentService(investmentRepository, portfolioRepository,
                portfolioRiskService, capitalGainsTaxService, bondAnalyticsService);
        when(investmentRepository.save(org.mockito.ArgumentMatchers.any(Investment.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void addInvestment_persistsFixedDepositFieldsForTaxComputation() {
        service.addInvestment("u", InvestmentType.FIXED_DEPOSIT, "HDFC-FD-1", "HDFC Bank FD",
                LocalDate.parse("2024-01-01"), BigDecimal.valueOf(1), BigDecimal.valueOf(100_000),
                "HDFC Bank", BigDecimal.valueOf(7.1), LocalDate.parse("2027-01-01"), null, null);

        ArgumentCaptor<Investment> captor = ArgumentCaptor.forClass(Investment.class);
        verify(investmentRepository).save(captor.capture());
        Investment saved = captor.getValue();

        assertEquals(0, saved.getInterestRate().compareTo(BigDecimal.valueOf(7.1)));
        assertEquals(LocalDate.parse("2027-01-01"), saved.getMaturityDate());
    }

    @Test
    void addInvestment_persistsInsurancePolicyFieldsForTaxComputation() {
        service.addInvestment("u", InvestmentType.INSURANCE_POLICY, "LIC-1", "LIC Jeevan Anand",
                LocalDate.parse("2020-01-01"), BigDecimal.ONE, BigDecimal.valueOf(50_000),
                "LIC", null, null, BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000));

        ArgumentCaptor<Investment> captor = ArgumentCaptor.forClass(Investment.class);
        verify(investmentRepository).save(captor.capture());
        Investment saved = captor.getValue();

        assertEquals(0, saved.getSumAssured().compareTo(BigDecimal.valueOf(1_000_000)));
        assertEquals(0, saved.getAnnualPremium().compareTo(BigDecimal.valueOf(50_000)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=InvestmentServiceTest`
Expected: FAIL to compile — `addInvestment` has no such 12-arg overload, and `Investment` has no `getInterestRate()`/`getMaturityDate()`/`getSumAssured()`/`getAnnualPremium()`.

- [ ] **Step 3: Add the 4 fields to `Investment`**

In `src/main/java/org/amit/finwise/investment/model/Investment.java`, insert after the `platform` field (after line 46, before `sector`):

```java
    @Column(name = "interest_rate", precision = 8, scale = 4)
    private BigDecimal interestRate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "sum_assured", precision = 19, scale = 4)
    private BigDecimal sumAssured;

    @Column(name = "annual_premium", precision = 19, scale = 4)
    private BigDecimal annualPremium;

```

(Lombok `@Data`/`@Builder` on the class already generates the getters/setters/builder methods for these — no other change needed in this file.)

- [ ] **Step 4: Add the 4 fields to the DTOs**

Replace the full contents of `AddInvestmentRequest.java`:

```java
package org.amit.finwise.investment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.amit.finwise.investment.enums.InvestmentType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record AddInvestmentRequest(
        @NotNull InvestmentType type,
        @NotBlank String symbol,
        @NotBlank String name,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal costPerUnit,
        String platform,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal sumAssured,
        BigDecimal annualPremium
) {}
```

Replace the full contents of `InvestmentResponse.java`:

```java
package org.amit.finwise.investment.dto;

import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.enums.RiskProfile;
import org.amit.finwise.investment.model.Investment;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvestmentResponse(
        Long id,
        InvestmentType type,
        String symbol,
        String name,
        String platform,
        String sector,
        LocalDate purchaseDate,
        BigDecimal quantity,
        BigDecimal costPerUnit,
        BigDecimal totalCost,
        BigDecimal currentPrice,
        BigDecimal currentValue,
        BigDecimal unrealizedGainLoss,
        BigDecimal gainLossPercentage,
        Boolean isActive,
        RiskProfile riskProfile,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal sumAssured,
        BigDecimal annualPremium
) {
    public static InvestmentResponse from(Investment i) {
        return new InvestmentResponse(
                i.getId(),
                i.getType(),
                i.getSymbol(),
                i.getName(),
                i.getPlatform(),
                i.getSector(),
                i.getPurchaseDate(),
                i.getQuantity(),
                i.getCostPerUnit(),
                i.getTotalCost(),
                i.getCurrentPrice(),
                i.getCurrentValue(),
                i.getUnrealizedGainLoss(),
                i.getGainLossPercentage(),
                i.getIsActive(),
                i.getRiskProfile(),
                i.getInterestRate(),
                i.getMaturityDate(),
                i.getSumAssured(),
                i.getAnnualPremium()
        );
    }
}
```

- [ ] **Step 5: Extend `InvestmentService.addInvestment`**

In `src/main/java/org/amit/finwise/investment/service/InvestmentService.java`, replace lines 49-62:

```java
    @Transactional
    public Investment addInvestment(String userId, InvestmentType type, String symbol,
                                    String name, LocalDate purchaseDate, BigDecimal quantity,
                                    BigDecimal costPerUnit, String platform) {
        BigDecimal totalCost = quantity.multiply(costPerUnit);
        Investment investment = Investment.builder()
                .userId(userId).type(type).symbol(symbol).name(name)
                .purchaseDate(purchaseDate).quantity(quantity).costPerUnit(costPerUnit)
                .totalCost(totalCost).currentPrice(costPerUnit).currentValue(totalCost)
                .platform(platform).unrealizedGainLoss(BigDecimal.ZERO).gainLossPercentage(BigDecimal.ZERO)
                .build();
        log.info("Added investment: {} - {} units at {}", name, quantity, costPerUnit);
        return investmentRepository.save(investment);
    }
```

with:

```java
    @Transactional
    public Investment addInvestment(String userId, InvestmentType type, String symbol,
                                    String name, LocalDate purchaseDate, BigDecimal quantity,
                                    BigDecimal costPerUnit, String platform,
                                    BigDecimal interestRate, LocalDate maturityDate,
                                    BigDecimal sumAssured, BigDecimal annualPremium) {
        BigDecimal totalCost = quantity.multiply(costPerUnit);
        Investment investment = Investment.builder()
                .userId(userId).type(type).symbol(symbol).name(name)
                .purchaseDate(purchaseDate).quantity(quantity).costPerUnit(costPerUnit)
                .totalCost(totalCost).currentPrice(costPerUnit).currentValue(totalCost)
                .platform(platform).unrealizedGainLoss(BigDecimal.ZERO).gainLossPercentage(BigDecimal.ZERO)
                .interestRate(interestRate).maturityDate(maturityDate)
                .sumAssured(sumAssured).annualPremium(annualPremium)
                .build();
        log.info("Added investment: {} - {} units at {}", name, quantity, costPerUnit);
        return investmentRepository.save(investment);
    }
```

- [ ] **Step 6: Update the controller call site**

In `src/main/java/org/amit/finwise/investment/controller/InvestmentController.java`, replace lines 28-36:

```java
    @PostMapping("/investment")
    public ResponseEntity<InvestmentResponse> addInvestment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AddInvestmentRequest request) {
        return ResponseEntity.ok(InvestmentResponse.from(
                investmentService.addInvestment(
                        principal.getUsername(), request.type(), request.symbol(), request.name(),
                        LocalDate.now(), request.quantity(), request.costPerUnit(), request.platform())));
    }
```

with:

```java
    @PostMapping("/investment")
    public ResponseEntity<InvestmentResponse> addInvestment(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody AddInvestmentRequest request) {
        return ResponseEntity.ok(InvestmentResponse.from(
                investmentService.addInvestment(
                        principal.getUsername(), request.type(), request.symbol(), request.name(),
                        LocalDate.now(), request.quantity(), request.costPerUnit(), request.platform(),
                        request.interestRate(), request.maturityDate(),
                        request.sumAssured(), request.annualPremium())));
    }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw test -Dtest=InvestmentServiceTest`
Expected: PASS (2 tests)

- [ ] **Step 8: Run the full existing suite to confirm no regression**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest,LotTrackingServiceTest,InvestmentControllerTest`
Expected: PASS (any pre-existing tests in these classes are unaffected by additive changes)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/model/Investment.java \
        src/main/java/org/amit/finwise/investment/dto/AddInvestmentRequest.java \
        src/main/java/org/amit/finwise/investment/dto/InvestmentResponse.java \
        src/main/java/org/amit/finwise/investment/service/InvestmentService.java \
        src/main/java/org/amit/finwise/investment/controller/InvestmentController.java \
        src/test/java/org/amit/finwise/investment/service/InvestmentServiceTest.java
git commit -m "feat(tax): add interestRate/maturityDate/sumAssured/annualPremium to Investment"
```

---

### Task 2: Regime split (`DEBT_SLAB` rename) + `EXEMPT` regime + PPF

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java`
- Test: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Consumes: `Investment.getType(): InvestmentType` (existing), `InvestmentType.PPF` (existing enum constant).
- Produces: `Regime` enum now has `EQUITY, DEBT_SLAB, INTEREST_INCOME, NON_EQUITY_FLAT, EXEMPT, EXCLUDED` (was `EQUITY, SLAB, EXCLUDED`). `TaxEstimate.exemptAmount(): BigDecimal` (new, appended field) — consumed by Task 7's integration test.

- [ ] **Step 1: Write the failing test**

Add to `CapitalGainsTaxServiceTest.java` (after the `equityFund_staysInEquityRegimeWithAssumptionNote` test, before the "Realized netting" section comment):

```java
    // ── PPF exemption ─────────────────────────────────────────────────────────

    @Test
    void ppf_isFullyExemptAndReportedNotDropped() {
        Investment ppf = Investment.builder()
                .userId(USER).type(InvestmentType.PPF).name("PPF Account")
                .purchaseDate(LocalDate.now().minusYears(5))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(500_000))
                .totalCost(BigDecimal.valueOf(500_000)).currentValue(BigDecimal.valueOf(650_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(ppf));

        assertEquals(650_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.exclusions().isEmpty(), "PPF must not be silently excluded");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("PPF_EXEMPT")));
        assertEquals(0.0, est.totalTaxIfSoldToday().doubleValue(), 1e-9);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#ppf_isFullyExemptAndReportedNotDropped`
Expected: FAIL to compile — `TaxEstimate.exemptAmount()` does not exist yet.

- [ ] **Step 3: Rename `SLAB` → `DEBT_SLAB` and add the 3 new regime values**

In `CapitalGainsTaxService.java`, replace:

```java
    private enum Regime { EQUITY, SLAB, EXCLUDED }
```

with:

```java
    private enum Regime { EQUITY, DEBT_SLAB, INTEREST_INCOME, NON_EQUITY_FLAT, EXEMPT, EXCLUDED }
```

- [ ] **Step 4: Update `regimeOf()` for the rename and add PPF**

Replace the existing `regimeOf` method:

```java
    private Regime regimeOf(Investment inv, List<String> notes) {
        InvestmentType type = inv.getType();
        if (type == InvestmentType.STOCK || type == InvestmentType.ETF) return Regime.EQUITY;
        if (type != InvestmentType.MUTUAL_FUND) return Regime.EXCLUDED;

        String name = inv.getName() != null ? inv.getName().toLowerCase(Locale.ROOT) : "";
        if (DEBT_FUND_NAME.matcher(name).find()) {
            notes.add("MF_CLASSIFIED_DEBT: " + inv.getName()
                    + " — taxed at slab rate (post-Apr-2023 debt MF rule); name-based classification");
            return Regime.SLAB;
        }
        notes.add("MF_ASSUMED_EQUITY: " + inv.getName()
                + " — equity-oriented (≥65% equity) assumed from scheme name");
        return Regime.EQUITY;
    }
```

with:

```java
    private Regime regimeOf(Investment inv, List<String> notes) {
        InvestmentType type = inv.getType();
        if (type == InvestmentType.STOCK || type == InvestmentType.ETF) return Regime.EQUITY;

        if (type == InvestmentType.MUTUAL_FUND) {
            String name = inv.getName() != null ? inv.getName().toLowerCase(Locale.ROOT) : "";
            if (DEBT_FUND_NAME.matcher(name).find()) {
                notes.add("MF_CLASSIFIED_DEBT: " + inv.getName()
                        + " — taxed at slab rate (post-Apr-2023 debt MF rule); name-based classification");
                return Regime.DEBT_SLAB;
            }
            notes.add("MF_ASSUMED_EQUITY: " + inv.getName()
                    + " — equity-oriented (≥65% equity) assumed from scheme name");
            return Regime.EQUITY;
        }

        if (type == InvestmentType.PPF) {
            notes.add("PPF_EXEMPT: " + inv.getName() + " — entire corpus tax-free under Section 10(11)");
            return Regime.EXEMPT;
        }

        return Regime.EXCLUDED;
    }
```

- [ ] **Step 5: Restructure `estimate()` to branch by regime before the mark-to-market null-check, and add the `EXEMPT` branch**

Replace the full `estimate` method:

```java
    public TaxEstimate estimate(List<Investment> activeInvestments) {
        List<HoldingTax> holdings = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        double stcgGains = 0;
        double ltcgGains = 0;
        double slabGains = 0;

        for (Investment inv : activeInvestments) {
            if (inv.getUnrealizedGainLoss() == null) continue;
            String label = inv.getSymbol() != null ? inv.getSymbol() : String.valueOf(inv.getType());

            Regime regime = regimeOf(inv, notes);
            if (regime == Regime.EXCLUDED) {
                exclusions.add(label);
                continue;
            }
            if (inv.getPurchaseDate() == null) {
                exclusions.add(label + " (no purchase date)");
                continue;
            }

            boolean longTerm = inv.getPurchaseDate().isBefore(LocalDate.now().minusYears(1));
            double gain = grandfatheredGain(inv, notes);

            if (gain > 0) {
                switch (regime) {
                    case EQUITY -> { if (longTerm) ltcgGains += gain; else stcgGains += gain; }
                    case SLAB   -> slabGains += gain;
                    default -> { }
                }
            }
            holdings.add(new HoldingTax(
                    inv.getSymbol(), inv.getPurchaseDate(),
                    regime == Regime.SLAB ? "SLAB" : (longTerm ? "LTCG" : "STCG"),
                    inv.getUnrealizedGainLoss()));
        }

        double stcgTax = stcgGains * stcgRate;
        double ltcgTax = Math.max(0, ltcgGains - ltcgExemption) * ltcgRate;
        double slabTax = slabGains * slabRate;
        double totalTax = stcgTax + ltcgTax + slabTax;

        return new TaxEstimate(
                rupees(stcgGains), rupees(ltcgGains),
                rupees(stcgTax), rupees(ltcgTax), rupees(totalTax),
                List.copyOf(holdings), List.copyOf(exclusions),
                rupees(slabGains), rupees(slabTax), List.copyOf(notes));
    }
```

with:

```java
    public TaxEstimate estimate(List<Investment> activeInvestments) {
        List<HoldingTax> holdings = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        double stcgGains = 0;
        double ltcgGains = 0;
        double slabGains = 0;
        double exemptAmount = 0;

        for (Investment inv : activeInvestments) {
            String label = inv.getSymbol() != null ? inv.getSymbol() : String.valueOf(inv.getType());
            Regime regime = regimeOf(inv, notes);

            if (regime == Regime.EXCLUDED) {
                exclusions.add(label);
                continue;
            }

            if (regime == Regime.EXEMPT) {
                BigDecimal value = inv.getCurrentValue() != null ? inv.getCurrentValue()
                        : (inv.getTotalCost() != null ? inv.getTotalCost() : BigDecimal.ZERO);
                exemptAmount += value.doubleValue();
                holdings.add(new HoldingTax(inv.getSymbol(), inv.getPurchaseDate(), "EXEMPT",
                        zeroIfNull(inv.getUnrealizedGainLoss())));
                continue;
            }

            // Remaining regimes (EQUITY / DEBT_SLAB) need a mark-to-market gain figure.
            if (inv.getUnrealizedGainLoss() == null) continue;
            if (inv.getPurchaseDate() == null) {
                exclusions.add(label + " (no purchase date)");
                continue;
            }

            boolean longTerm = inv.getPurchaseDate().isBefore(LocalDate.now().minusYears(1));
            double gain = grandfatheredGain(inv, notes);

            if (gain > 0) {
                switch (regime) {
                    case EQUITY -> { if (longTerm) ltcgGains += gain; else stcgGains += gain; }
                    case DEBT_SLAB -> slabGains += gain;
                    default -> { }
                }
            }
            holdings.add(new HoldingTax(
                    inv.getSymbol(), inv.getPurchaseDate(),
                    regime == Regime.DEBT_SLAB ? "SLAB" : (longTerm ? "LTCG" : "STCG"),
                    inv.getUnrealizedGainLoss()));
        }

        double stcgTax = stcgGains * stcgRate;
        double ltcgTax = Math.max(0, ltcgGains - ltcgExemption) * ltcgRate;
        double slabTax = slabGains * slabRate;
        double totalTax = stcgTax + ltcgTax + slabTax;

        return new TaxEstimate(
                rupees(stcgGains), rupees(ltcgGains),
                rupees(stcgTax), rupees(ltcgTax), rupees(totalTax),
                List.copyOf(holdings), List.copyOf(exclusions),
                rupees(slabGains), rupees(slabTax), List.copyOf(notes),
                rupees(exemptAmount));
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
```

- [ ] **Step 6: Add the `exemptAmount` field to `TaxEstimate`**

Replace the `TaxEstimate` record:

```java
    public record TaxEstimate(
            BigDecimal stcgGains,            // unrealized gains in STCG bucket
            BigDecimal ltcgGains,            // unrealized gains in LTCG bucket (post-grandfathering)
            BigDecimal stcgTaxIfSoldToday,
            BigDecimal ltcgTaxIfSoldToday,   // after annual exemption
            BigDecimal totalTaxIfSoldToday,
            List<HoldingTax> holdings,
            List<String> exclusions,         // assets outside equity/slab regimes
            BigDecimal slabGains,            // debt-MF gains taxed at slab rate
            BigDecimal slabTaxIfSoldToday,
            List<String> notes               // GRANDFATHERED / MF_CLASSIFIED_* disclosures
    ) {}
```

with:

```java
    public record TaxEstimate(
            BigDecimal stcgGains,            // unrealized gains in STCG bucket
            BigDecimal ltcgGains,            // unrealized gains in LTCG bucket (post-grandfathering)
            BigDecimal stcgTaxIfSoldToday,
            BigDecimal ltcgTaxIfSoldToday,   // after annual exemption
            BigDecimal totalTaxIfSoldToday,
            List<HoldingTax> holdings,
            List<String> exclusions,         // assets outside every known regime (e.g. crypto, real estate)
            BigDecimal slabGains,            // debt-MF gains taxed at slab rate
            BigDecimal slabTaxIfSoldToday,
            List<String> notes,              // GRANDFATHERED / MF_CLASSIFIED_* / PPF_EXEMPT disclosures
            BigDecimal exemptAmount          // value of fully tax-exempt holdings (informational, zero tax)
    ) {}
```

- [ ] **Step 7: Run the new test and the full existing suite**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest`
Expected: PASS — all 8 tests (5 pre-existing + 1 debt-fund + 1 equity-fund + the new PPF test) green. The pre-existing tests are unaffected because `EQUITY`/`DEBT_SLAB` behavior and field order for the first 10 `TaxEstimate` fields are unchanged.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java \
        src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "feat(tax): split Regime into 6 values, add EXEMPT regime and PPF"
```

---

### Task 3: `INTEREST_INCOME` regime — FIXED_DEPOSIT & POST_OFFICE_SCHEME

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java`
- Test: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Consumes: `Investment.getInterestRate(): BigDecimal` (from Task 1), `Investment.getCostPerUnit()`, `.getQuantity()`, `.getPlatform()` (existing).
- Produces: `TaxEstimate.interestIncomeGains(): BigDecimal`, `.interestIncomeTax(): BigDecimal` (new, appended fields).

- [ ] **Step 1: Write the failing tests**

Add to `CapitalGainsTaxServiceTest.java` after the PPF test:

```java
    // ── Interest income (FD / post office) ──────────────────────────────────

    @Test
    void fixedDeposit_computesAnnualInterestAtSlabRate() {
        Investment fd = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD")
                .purchaseDate(LocalDate.now().minusYears(1))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(100_000))
                .interestRate(BigDecimal.valueOf(7))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(fd));

        assertEquals(7_000.0, est.interestIncomeGains().doubleValue(), 1e-9, "100,000 × 7%");
        assertEquals(2_100.0, est.interestIncomeTax().doubleValue(), 1e-9, "7,000 × 30% slab");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INTEREST_SIMPLE_ANNUAL_ASSUMED")));
    }

    @Test
    void postOfficeScheme_missingInterestRate_degradesGracefullyWithNote() {
        Investment nsc = Investment.builder()
                .userId(USER).type(InvestmentType.POST_OFFICE_SCHEME).name("NSC")
                .purchaseDate(LocalDate.now().minusYears(1))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(nsc));

        assertEquals(0.0, est.interestIncomeGains().doubleValue(), 1e-9);
        assertTrue(est.exclusions().contains("NSC"));
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INTEREST_RATE_MISSING")));
    }

    @Test
    void interestIncome_sameHighPlatformInterest_flagsLikelyTds() {
        Investment fd1 = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD 1")
                .purchaseDate(LocalDate.now().minusYears(1)).platform("HDFC Bank")
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(300_000))
                .interestRate(BigDecimal.valueOf(7)).build();
        Investment fd2 = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("HDFC FD 2")
                .purchaseDate(LocalDate.now().minusYears(1)).platform("HDFC Bank")
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(300_000))
                .interestRate(BigDecimal.valueOf(7)).build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(fd1, fd2));

        // 300,000×7% × 2 = 42,000 from the same platform, over the 40,000 TDS threshold
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("TDS_LIKELY") && n.contains("HDFC Bank")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#fixedDeposit_computesAnnualInterestAtSlabRate`
Expected: FAIL to compile — `TaxEstimate.interestIncomeGains()`/`.interestIncomeTax()` don't exist.

- [ ] **Step 3: Route FIXED_DEPOSIT / POST_OFFICE_SCHEME in `regimeOf()`**

Insert into `regimeOf()`, immediately after the `if (type == InvestmentType.PPF) { ... }` block:

```java

        if (type == InvestmentType.FIXED_DEPOSIT || type == InvestmentType.POST_OFFICE_SCHEME) {
            return Regime.INTEREST_INCOME;
        }
```

- [ ] **Step 4: Add the `INTEREST_INCOME` branch, TDS grouping, and the `interestIncomeTax`/`Gains` fields**

Replace the `estimate` method (as left by Task 2) with:

```java
    public TaxEstimate estimate(List<Investment> activeInvestments) {
        List<HoldingTax> holdings = new ArrayList<>();
        List<String> exclusions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        double stcgGains = 0;
        double ltcgGains = 0;
        double slabGains = 0;
        double exemptAmount = 0;
        double interestIncomeGains = 0;
        Map<String, Double> interestByPlatform = new HashMap<>();

        for (Investment inv : activeInvestments) {
            String label = inv.getSymbol() != null ? inv.getSymbol() : inv.getName();
            Regime regime = regimeOf(inv, notes);

            if (regime == Regime.EXCLUDED) {
                exclusions.add(label);
                continue;
            }

            if (regime == Regime.EXEMPT) {
                BigDecimal value = inv.getCurrentValue() != null ? inv.getCurrentValue()
                        : (inv.getTotalCost() != null ? inv.getTotalCost() : BigDecimal.ZERO);
                exemptAmount += value.doubleValue();
                holdings.add(new HoldingTax(inv.getSymbol(), inv.getPurchaseDate(), "EXEMPT",
                        zeroIfNull(inv.getUnrealizedGainLoss())));
                continue;
            }

            if (regime == Regime.INTEREST_INCOME) {
                if (inv.getInterestRate() == null) {
                    notes.add("INTEREST_RATE_MISSING: " + label
                            + " — no interestRate on record, excluded from interest-income estimate");
                    exclusions.add(label);
                    continue;
                }
                double principal = inv.getCostPerUnit().multiply(inv.getQuantity()).doubleValue();
                double annualInterest = principal * inv.getInterestRate().doubleValue() / 100.0;
                interestIncomeGains += annualInterest;
                String payer = inv.getPlatform() != null ? inv.getPlatform() : "UNKNOWN";
                interestByPlatform.merge(payer, annualInterest, Double::sum);
                notes.add("INTEREST_SIMPLE_ANNUAL_ASSUMED: " + label
                        + " — simple annual accrual at " + inv.getInterestRate() + "%, not compounded");
                holdings.add(new HoldingTax(inv.getSymbol(), inv.getPurchaseDate(), "INTEREST_INCOME",
                        rupees(annualInterest)));
                continue;
            }

            // Remaining regimes (EQUITY / DEBT_SLAB) need a mark-to-market gain figure.
            if (inv.getUnrealizedGainLoss() == null) continue;
            if (inv.getPurchaseDate() == null) {
                exclusions.add(label + " (no purchase date)");
                continue;
            }

            boolean longTerm = inv.getPurchaseDate().isBefore(LocalDate.now().minusYears(1));
            double gain = grandfatheredGain(inv, notes);

            if (gain > 0) {
                switch (regime) {
                    case EQUITY -> { if (longTerm) ltcgGains += gain; else stcgGains += gain; }
                    case DEBT_SLAB -> slabGains += gain;
                    default -> { }
                }
            }
            holdings.add(new HoldingTax(
                    inv.getSymbol(), inv.getPurchaseDate(),
                    regime == Regime.DEBT_SLAB ? "SLAB" : (longTerm ? "LTCG" : "STCG"),
                    inv.getUnrealizedGainLoss()));
        }

        for (Map.Entry<String, Double> e : interestByPlatform.entrySet()) {
            if (e.getValue() > TDS_THRESHOLD) {
                notes.add(String.format(
                        "TDS_LIKELY: interest from %s (₹%.0f) exceeds the ₹40,000 TDS threshold — "
                        + "the payer likely deducted 10%% TDS; verify against Form 26AS",
                        e.getKey(), e.getValue()));
            }
        }

        double stcgTax = stcgGains * stcgRate;
        double ltcgTax = Math.max(0, ltcgGains - ltcgExemption) * ltcgRate;
        double slabTax = slabGains * slabRate;
        double interestIncomeTax = interestIncomeGains * slabRate;
        double totalTax = stcgTax + ltcgTax + slabTax + interestIncomeTax;

        return new TaxEstimate(
                rupees(stcgGains), rupees(ltcgGains),
                rupees(stcgTax), rupees(ltcgTax), rupees(totalTax),
                List.copyOf(holdings), List.copyOf(exclusions),
                rupees(slabGains), rupees(slabTax), List.copyOf(notes),
                rupees(exemptAmount), rupees(interestIncomeGains), rupees(interestIncomeTax));
    }
```

Note the label fallback changed from `String.valueOf(inv.getType())` to `inv.getName()` — every `InvestmentType` in scope now has meaningful exclusion/note text via the name field, which is `nullable = false` on the entity, so this is always safe.

- [ ] **Step 5: Add the `TDS_THRESHOLD` constant and the two new `Map`/`HashMap` imports**

Add near the top of the class, alongside the existing `DEBT_FUND_NAME` pattern constant:

```java
    private static final double TDS_THRESHOLD = 40_000;
```

Add to the import block:

```java
import java.util.HashMap;
import java.util.Map;
```

- [ ] **Step 6: Add `interestIncomeGains`/`interestIncomeTax` to `TaxEstimate`**

Replace the `TaxEstimate` record (as left by Task 2):

```java
    public record TaxEstimate(
            BigDecimal stcgGains,            // unrealized gains in STCG bucket
            BigDecimal ltcgGains,            // unrealized gains in LTCG bucket (post-grandfathering)
            BigDecimal stcgTaxIfSoldToday,
            BigDecimal ltcgTaxIfSoldToday,   // after annual exemption
            BigDecimal totalTaxIfSoldToday,
            List<HoldingTax> holdings,
            List<String> exclusions,         // assets outside every known regime (e.g. crypto, real estate)
            BigDecimal slabGains,            // debt-MF gains taxed at slab rate
            BigDecimal slabTaxIfSoldToday,
            List<String> notes,              // GRANDFATHERED / MF_CLASSIFIED_* / PPF_EXEMPT disclosures
            BigDecimal exemptAmount          // value of fully tax-exempt holdings (informational, zero tax)
    ) {}
```

with:

```java
    public record TaxEstimate(
            BigDecimal stcgGains,            // unrealized gains in STCG bucket
            BigDecimal ltcgGains,            // unrealized gains in LTCG bucket (post-grandfathering)
            BigDecimal stcgTaxIfSoldToday,
            BigDecimal ltcgTaxIfSoldToday,   // after annual exemption
            BigDecimal totalTaxIfSoldToday,
            List<HoldingTax> holdings,
            List<String> exclusions,         // assets outside every known regime (e.g. crypto, real estate)
            BigDecimal slabGains,            // debt-MF gains taxed at slab rate
            BigDecimal slabTaxIfSoldToday,
            List<String> notes,              // GRANDFATHERED / MF_CLASSIFIED_* / PPF_EXEMPT disclosures
            BigDecimal exemptAmount,         // value of fully tax-exempt holdings (informational, zero tax)
            BigDecimal interestIncomeGains,  // annual interest, FD/post-office ("Income from Other Sources")
            BigDecimal interestIncomeTax     // interestIncomeGains × slab rate
    ) {}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest`
Expected: PASS — all tests green, including the 3 new interest-income tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java \
        src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "feat(tax): add INTEREST_INCOME regime for FD and post-office schemes"
```

---

### Task 4: `INSURANCE_POLICY` — Section 10(10D) exemption test

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java`
- Test: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Consumes: `Investment.getSumAssured()`, `.getAnnualPremium()` (from Task 1). No new `TaxEstimate` fields — routes into the existing `EXEMPT` and `DEBT_SLAB` buckets.

- [ ] **Step 1: Write the failing tests**

Add to `CapitalGainsTaxServiceTest.java` after the interest-income tests:

```java
    // ── Insurance (Section 10(10D)) ──────────────────────────────────────────

    @Test
    void insurance_premiumWithinTenPercentThreshold_isExempt() {
        // premium 40,000 / sumAssured 500,000 = 8% ≤ 10% threshold (post-2012 policy)
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("LIC Term Plan")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(600_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(40_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(600_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_EXEMPT_10_10D")));
    }

    @Test
    void insurance_premiumExceedsTenPercentThreshold_isTaxedAtSlabRate() {
        // premium 80,000 / sumAssured 500,000 = 16% > 10% threshold (post-2012 policy)
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("ULIP Growth")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(600_000))
                .unrealizedGainLoss(BigDecimal.valueOf(200_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(80_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(200_000.0, est.slabGains().doubleValue(), 1e-9);
        assertEquals(60_000.0, est.slabTaxIfSoldToday().doubleValue(), 1e-9, "200,000 × 30% slab");
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_TAXABLE_10_10D")));
    }

    @Test
    void insurance_prePost2012Threshold_isTwentyPercentNotTen() {
        // premium 90,000 / sumAssured 500,000 = 18% — exempt under the pre-2012 20% rule,
        // would have been taxable under the post-2012 10% rule
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("Old LIC Endowment")
                .purchaseDate(LocalDate.parse("2010-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(550_000))
                .sumAssured(BigDecimal.valueOf(500_000)).annualPremium(BigDecimal.valueOf(90_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(550_000.0, est.exemptAmount().doubleValue(), 1e-9);
    }

    @Test
    void insurance_missingSumAssured_assumedExemptWithDisclosure() {
        Investment policy = Investment.builder()
                .userId(USER).type(InvestmentType.INSURANCE_POLICY).name("Unknown Policy")
                .purchaseDate(LocalDate.parse("2015-01-01"))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentValue(BigDecimal.valueOf(500_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(policy));

        assertEquals(500_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("INSURANCE_ASSUMED_EXEMPT_10_10D")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#insurance_premiumWithinTenPercentThreshold_isExempt`
Expected: FAIL — `INSURANCE_POLICY` currently falls through to `Regime.EXCLUDED`, so `exemptAmount` stays 0 and the expected note is never emitted.

- [ ] **Step 3: Route `INSURANCE_POLICY` in `regimeOf()`**

Insert into `regimeOf()`, immediately after the `FIXED_DEPOSIT`/`POST_OFFICE_SCHEME` block added in Task 3:

```java

        if (type == InvestmentType.INSURANCE_POLICY) {
            if (inv.getAnnualPremium() == null || inv.getSumAssured() == null
                    || inv.getSumAssured().signum() == 0) {
                notes.add("INSURANCE_ASSUMED_EXEMPT_10_10D: " + inv.getName()
                        + " — premium/sum-assured not on record; exemption assumed "
                        + "(cannot verify the Section 10(10D) threshold)");
                return Regime.EXEMPT;
            }
            boolean postApril2012 = inv.getPurchaseDate() != null
                    && !inv.getPurchaseDate().isBefore(LocalDate.of(2012, 4, 1));
            double threshold = postApril2012 ? 0.10 : 0.20;
            double ratio = inv.getAnnualPremium().doubleValue() / inv.getSumAssured().doubleValue();
            if (ratio <= threshold) {
                notes.add(String.format(Locale.ROOT,
                        "INSURANCE_EXEMPT_10_10D: %s — premium/sum-assured ratio %.4f within the %.0f%% threshold",
                        inv.getName(), ratio, threshold * 100));
                return Regime.EXEMPT;
            }
            notes.add(String.format(Locale.ROOT,
                    "INSURANCE_TAXABLE_10_10D: %s — premium/sum-assured ratio %.4f exceeds the %.0f%% threshold; "
                    + "proceeds taxed at slab rate (ULIP-specific Section 112A treatment simplified to slab rate here)",
                    inv.getName(), ratio, threshold * 100));
            return Regime.DEBT_SLAB;
        }
```

No changes to `estimate()` are needed — `INSURANCE_POLICY` now resolves to either `EXEMPT` or `DEBT_SLAB`, both of which are already fully handled by the loop.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest`
Expected: PASS — all tests green, including the 4 new insurance tests. Note that the "taxable" test relies on the existing `grandfatheredGain()` path (since `DEBT_SLAB` still calls it) — verify `grandfatheredGain` returns the raw `unrealizedGainLoss` unchanged for a purchase date after the grandfathering cutoff (2018-01-01 in the test is before 2018-01-31, so double check: the test's `purchaseDate` is `2015-01-01`, which is *before* the grandfathering date, meaning `grandfatheredGain` will attempt a `stockPriceService.closeOn()` lookup keyed on `inv.getSymbol()`. Since this test's `Investment` has no `symbol` set, `grandfatheredGain`'s null-check on `inv.getSymbol()` will trigger the `GRANDFATHERING_SKIPPED` fallback path and return the raw gain — confirm this by re-reading `grandfatheredGain` (lines 136-162 as of Task 1) before running; the raw-gain fallback is exactly what the `insurance_premiumExceedsTenPercentThreshold_isTaxedAtSlabRate` test expects (200,000 unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java \
        src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "feat(tax): add Section 10(10D) exemption test for INSURANCE_POLICY"
```

---

### Task 5: `NON_EQUITY_FLAT` regime — GOLD / BOND / COMMODITY + SGB exemption

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java`
- Modify: `src/main/resources/application-dev.properties:309-317`
- Test: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Produces: `TaxEstimate.nonEquityFlatGains(): BigDecimal`, `.nonEquityFlatTax(): BigDecimal` (new, appended fields). New constructor params `nonEquityLtcgRate: double`, `nonEquityLtMonths: int` — **consumed by Task 6** (the `realizedTax()` extension needs the same two fields).

- [ ] **Step 1: Write the failing tests**

Add to `CapitalGainsTaxServiceTest.java` after the insurance tests:

```java
    // ── Non-equity flat-rate assets (gold / bond / commodity) ────────────────

    @Test
    void gold_longTerm_flatRateNoIndexation() {
        // Held 25 months (> 24-month LT threshold), gain 100,000 × 12.5% flat = 12,500
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold")
                .purchaseDate(LocalDate.now().minusMonths(25))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(100_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(12_500.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "100,000 × 12.5% flat, no indexation");
    }

    @Test
    void gold_shortTerm_taxedAtSlabRateNotFlatRate() {
        // Held 23 months (< 24-month LT threshold) → slab rate, not the 12.5% LT rate
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold")
                .purchaseDate(LocalDate.now().minusMonths(23))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(30_000.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "100,000 × 30% slab, short-term");
    }

    @Test
    void bondAndCommodity_alsoUseNonEquityFlatRegime() {
        Investment bond = Investment.builder()
                .userId(USER).type(InvestmentType.BOND).name("REC Bond")
                .purchaseDate(LocalDate.now().minusMonths(30))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(100_000))
                .currentPrice(BigDecimal.valueOf(120_000))
                .unrealizedGainLoss(BigDecimal.valueOf(20_000))
                .build();
        Investment commodity = Investment.builder()
                .userId(USER).type(InvestmentType.COMMODITY).name("Silver ETF")
                .purchaseDate(LocalDate.now().minusMonths(30))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .currentPrice(BigDecimal.valueOf(60_000))
                .unrealizedGainLoss(BigDecimal.valueOf(10_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(bond, commodity));

        assertEquals(30_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(3_750.0, est.nonEquityFlatTax().doubleValue(), 1e-9, "30,000 × 12.5%");
    }

    @Test
    void sovereignGoldBond_nameMatch_isExemptNotFlatRate() {
        Investment sgb = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Sovereign Gold Bond 2031 Series IV")
                .purchaseDate(LocalDate.now().minusYears(3))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(50_000))
                .currentValue(BigDecimal.valueOf(70_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(sgb));

        assertEquals(70_000.0, est.exemptAmount().doubleValue(), 1e-9);
        assertEquals(0.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertTrue(est.notes().stream().anyMatch(n -> n.startsWith("GOLD_ASSUMED_SGB_EXEMPT")));
    }

    @Test
    void physicalGold_nameDoesNotMatchSgb_usesFlatRateRegime() {
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold Coin")
                .purchaseDate(LocalDate.now().minusMonths(25))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(400_000))
                .currentPrice(BigDecimal.valueOf(500_000))
                .unrealizedGainLoss(BigDecimal.valueOf(100_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est = service.estimate(List.of(gold));

        assertEquals(100_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(0.0, est.exemptAmount().doubleValue(), 1e-9);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#gold_longTerm_flatRateNoIndexation`
Expected: FAIL to compile — `TaxEstimate.nonEquityFlatGains()`/`.nonEquityFlatTax()` don't exist, and GOLD/BOND/COMMODITY currently resolve to `EXCLUDED`.

- [ ] **Step 3: Add the `SGB_NAME` pattern and the two new constructor params**

Add alongside the existing `DEBT_FUND_NAME` pattern:

```java
    private static final Pattern SGB_NAME = Pattern.compile(
            "sovereign gold bond|\\bsgb\\b", Pattern.CASE_INSENSITIVE);
```

Replace the constructor (as left by prior tasks — field declarations and constructor body both need the 2 new params):

```java
    private final StockPriceService stockPriceService;
    private final LotTrackingService lotTrackingService;
    private final double stcgRate;
    private final double ltcgRate;
    private final double ltcgExemption;
    private final double slabRate;
    private final LocalDate grandfatheringDate;
```

with:

```java
    private final StockPriceService stockPriceService;
    private final LotTrackingService lotTrackingService;
    private final double stcgRate;
    private final double ltcgRate;
    private final double ltcgExemption;
    private final double slabRate;
    private final LocalDate grandfatheringDate;
    private final double nonEquityLtcgRate;
    private final int nonEquityLtMonths;
```

and replace the constructor body:

```java
    public CapitalGainsTaxService(
            StockPriceService stockPriceService,
            LotTrackingService lotTrackingService,
            @Value("${cfo.tax.stcg-rate:0.20}") double stcgRate,
            @Value("${cfo.tax.ltcg-rate:0.125}") double ltcgRate,
            @Value("${cfo.tax.ltcg-exemption:125000}") double ltcgExemption,
            @Value("${cfo.tax.slab-rate:0.30}") double slabRate,
            @Value("${cfo.tax.grandfathering-date:2018-01-31}") String grandfatheringDate) {
        this.stockPriceService = stockPriceService;
        this.lotTrackingService = lotTrackingService;
        this.stcgRate = stcgRate;
        this.ltcgRate = ltcgRate;
        this.ltcgExemption = ltcgExemption;
        this.slabRate = slabRate;
        this.grandfatheringDate = LocalDate.parse(grandfatheringDate);
    }
```

with:

```java
    public CapitalGainsTaxService(
            StockPriceService stockPriceService,
            LotTrackingService lotTrackingService,
            @Value("${cfo.tax.stcg-rate:0.20}") double stcgRate,
            @Value("${cfo.tax.ltcg-rate:0.125}") double ltcgRate,
            @Value("${cfo.tax.ltcg-exemption:125000}") double ltcgExemption,
            @Value("${cfo.tax.slab-rate:0.30}") double slabRate,
            @Value("${cfo.tax.grandfathering-date:2018-01-31}") String grandfatheringDate,
            @Value("${cfo.tax.non-equity-ltcg-rate:0.125}") double nonEquityLtcgRate,
            @Value("${cfo.tax.non-equity-lt-months:24}") int nonEquityLtMonths) {
        this.stockPriceService = stockPriceService;
        this.lotTrackingService = lotTrackingService;
        this.stcgRate = stcgRate;
        this.ltcgRate = ltcgRate;
        this.ltcgExemption = ltcgExemption;
        this.slabRate = slabRate;
        this.grandfatheringDate = LocalDate.parse(grandfatheringDate);
        this.nonEquityLtcgRate = nonEquityLtcgRate;
        this.nonEquityLtMonths = nonEquityLtMonths;
    }
```

- [ ] **Step 4: Update the test's `setUp()` for the 2 new constructor params**

In `CapitalGainsTaxServiceTest.java`, replace:

```java
        service = new CapitalGainsTaxService(stockPriceService, lotTrackingService,
                0.20, 0.125, 125_000, 0.30, "2018-01-31");
```

with:

```java
        service = new CapitalGainsTaxService(stockPriceService, lotTrackingService,
                0.20, 0.125, 125_000, 0.30, "2018-01-31", 0.125, 24);
```

- [ ] **Step 5: Route GOLD / BOND / COMMODITY in `regimeOf()`**

Insert into `regimeOf()`, immediately after the `INSURANCE_POLICY` block added in Task 4:

```java

        if (type == InvestmentType.GOLD || type == InvestmentType.BOND || type == InvestmentType.COMMODITY) {
            String name = inv.getName() != null ? inv.getName().toLowerCase(Locale.ROOT) : "";
            if (type == InvestmentType.GOLD && SGB_NAME.matcher(name).find()) {
                notes.add("GOLD_ASSUMED_SGB_EXEMPT: " + inv.getName()
                        + " — name matches Sovereign Gold Bond; assumed exempt under Section 47(viic) "
                        + "(RBI redemption at maturity is exempt; taxable if sold early in the secondary market)");
                return Regime.EXEMPT;
            }
            return Regime.NON_EQUITY_FLAT;
        }
```

- [ ] **Step 6: Add the `NON_EQUITY_FLAT` branch to `estimate()`**

Replace the "Remaining regimes (EQUITY / DEBT_SLAB) need a mark-to-market gain figure." block (as left by Task 3) — i.e. replace from that comment through the end of the `for` loop body:

```java
            // Remaining regimes (EQUITY / DEBT_SLAB) need a mark-to-market gain figure.
            if (inv.getUnrealizedGainLoss() == null) continue;
            if (inv.getPurchaseDate() == null) {
                exclusions.add(label + " (no purchase date)");
                continue;
            }

            boolean longTerm = inv.getPurchaseDate().isBefore(LocalDate.now().minusYears(1));
            double gain = grandfatheredGain(inv, notes);

            if (gain > 0) {
                switch (regime) {
                    case EQUITY -> { if (longTerm) ltcgGains += gain; else stcgGains += gain; }
                    case DEBT_SLAB -> slabGains += gain;
                    default -> { }
                }
            }
            holdings.add(new HoldingTax(
                    inv.getSymbol(), inv.getPurchaseDate(),
                    regime == Regime.DEBT_SLAB ? "SLAB" : (longTerm ? "LTCG" : "STCG"),
                    inv.getUnrealizedGainLoss()));
        }
```

with:

```java
            // Remaining regimes (EQUITY / DEBT_SLAB / NON_EQUITY_FLAT) need a mark-to-market gain figure.
            if (inv.getUnrealizedGainLoss() == null) continue;
            if (inv.getPurchaseDate() == null) {
                exclusions.add(label + " (no purchase date)");
                continue;
            }

            if (regime == Regime.NON_EQUITY_FLAT) {
                boolean ltNonEquity = inv.getPurchaseDate().isBefore(LocalDate.now().minusMonths(nonEquityLtMonths));
                double flatGain = inv.getUnrealizedGainLoss().doubleValue();
                if (flatGain > 0) {
                    nonEquityFlatGains += flatGain;
                    nonEquityFlatTax += ltNonEquity ? flatGain * nonEquityLtcgRate : flatGain * slabRate;
                }
                holdings.add(new HoldingTax(inv.getSymbol(), inv.getPurchaseDate(),
                        ltNonEquity ? "LTCG_FLAT" : "STCG_SLAB", inv.getUnrealizedGainLoss()));
                continue;
            }

            boolean longTerm = inv.getPurchaseDate().isBefore(LocalDate.now().minusYears(1));
            double gain = grandfatheredGain(inv, notes);

            if (gain > 0) {
                switch (regime) {
                    case EQUITY -> { if (longTerm) ltcgGains += gain; else stcgGains += gain; }
                    case DEBT_SLAB -> slabGains += gain;
                    default -> { }
                }
            }
            holdings.add(new HoldingTax(
                    inv.getSymbol(), inv.getPurchaseDate(),
                    regime == Regime.DEBT_SLAB ? "SLAB" : (longTerm ? "LTCG" : "STCG"),
                    inv.getUnrealizedGainLoss()));
        }
```

Also add the two new accumulator declarations at the top of `estimate()` (alongside `exemptAmount`/`interestIncomeGains`):

```java
        double nonEquityFlatGains = 0;
        double nonEquityFlatTax = 0;
```

And update the final `return new TaxEstimate(...)` call (the `totalTax` computation and constructor call, as left by Task 3):

```java
        double stcgTax = stcgGains * stcgRate;
        double ltcgTax = Math.max(0, ltcgGains - ltcgExemption) * ltcgRate;
        double slabTax = slabGains * slabRate;
        double interestIncomeTax = interestIncomeGains * slabRate;
        double totalTax = stcgTax + ltcgTax + slabTax + interestIncomeTax;

        return new TaxEstimate(
                rupees(stcgGains), rupees(ltcgGains),
                rupees(stcgTax), rupees(ltcgTax), rupees(totalTax),
                List.copyOf(holdings), List.copyOf(exclusions),
                rupees(slabGains), rupees(slabTax), List.copyOf(notes),
                rupees(exemptAmount), rupees(interestIncomeGains), rupees(interestIncomeTax));
```

with:

```java
        double stcgTax = stcgGains * stcgRate;
        double ltcgTax = Math.max(0, ltcgGains - ltcgExemption) * ltcgRate;
        double slabTax = slabGains * slabRate;
        double interestIncomeTax = interestIncomeGains * slabRate;
        double totalTax = stcgTax + ltcgTax + slabTax + interestIncomeTax + nonEquityFlatTax;

        return new TaxEstimate(
                rupees(stcgGains), rupees(ltcgGains),
                rupees(stcgTax), rupees(ltcgTax), rupees(totalTax),
                List.copyOf(holdings), List.copyOf(exclusions),
                rupees(slabGains), rupees(slabTax), List.copyOf(notes),
                rupees(exemptAmount), rupees(interestIncomeGains), rupees(interestIncomeTax),
                rupees(nonEquityFlatGains), rupees(nonEquityFlatTax));
```

- [ ] **Step 7: Add `nonEquityFlatGains`/`nonEquityFlatTax` to `TaxEstimate`**

Append the 2 new fields to the `TaxEstimate` record (as left by Task 3):

```java
    public record TaxEstimate(
            BigDecimal stcgGains,
            BigDecimal ltcgGains,
            BigDecimal stcgTaxIfSoldToday,
            BigDecimal ltcgTaxIfSoldToday,
            BigDecimal totalTaxIfSoldToday,
            List<HoldingTax> holdings,
            List<String> exclusions,
            BigDecimal slabGains,
            BigDecimal slabTaxIfSoldToday,
            List<String> notes,
            BigDecimal exemptAmount,
            BigDecimal interestIncomeGains,
            BigDecimal interestIncomeTax,
            BigDecimal nonEquityFlatGains,    // GOLD/BOND/COMMODITY gains, flat rate, no indexation
            BigDecimal nonEquityFlatTax
    ) {}
```

- [ ] **Step 8: Add the 2 new properties**

In `src/main/resources/application-dev.properties`, replace lines 309-317:

```properties
# ============ Capital Gains Tax (cfo.tax.*) ============
# Post-Budget-2024 equity regime (Sec 111A/112A)
cfo.tax.stcg-rate=0.20
cfo.tax.ltcg-rate=0.125
cfo.tax.ltcg-exemption=125000
# Investor's marginal slab rate, applied to debt-MF gains (post-Apr-2023 rule)
cfo.tax.slab-rate=0.30
# Sec 55(2)(ac) grandfathering cut-off for equity cost step-up
cfo.tax.grandfathering-date=2018-01-31
```

with:

```properties
# ============ Capital Gains Tax (cfo.tax.*) ============
# Post-Budget-2024 equity regime (Sec 111A/112A)
cfo.tax.stcg-rate=0.20
cfo.tax.ltcg-rate=0.125
cfo.tax.ltcg-exemption=125000
# Investor's marginal slab rate, applied to debt-MF gains (post-Apr-2023 rule)
cfo.tax.slab-rate=0.30
# Sec 55(2)(ac) grandfathering cut-off for equity cost step-up
cfo.tax.grandfathering-date=2018-01-31
# Non-equity capital assets (gold/bond/commodity): flat LTCG rate, no indexation.
# The Aug-2024 indexation-choice carve-out is real-estate-only — do not apply it here.
cfo.tax.non-equity-ltcg-rate=0.125
cfo.tax.non-equity-lt-months=24
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest`
Expected: PASS — all tests green, including the 6 new non-equity-flat tests.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java \
        src/main/resources/application-dev.properties \
        src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "feat(tax): add NON_EQUITY_FLAT regime for gold/bond/commodity, SGB exemption"
```

---

### Task 6: Realized-sale support for `NON_EQUITY_FLAT` (LotTrackingService type-awareness)

**Files:**
- Modify: `src/main/java/org/amit/finwise/investment/repository/InvestmentRepository.java`
- Modify: `src/main/java/org/amit/finwise/investment/service/LotTrackingService.java`
- Modify: `src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java`
- Modify: `src/test/java/org/amit/finwise/investment/service/LotTrackingServiceTest.java`
- Modify: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Consumes: `nonEquityLtcgRate`, `nonEquityLtMonths`, `slabRate` (from Task 5, already fields on `CapitalGainsTaxService`).
- Produces: `LotTrackingService.RealizedGain.investmentType(): InvestmentType` (new field on existing record — **breaking for direct constructors**, see Step 4). `RealizedTaxSummary.nonEquityFlatGains()`, `.nonEquityFlatTax()` (new, appended fields).

- [ ] **Step 1: Add `findByUserId` to `InvestmentRepository`**

In `InvestmentRepository.java`, add after the `findBySymbol` query method:

```java

    List<Investment> findByUserId(String userId);
```

(A plain derived query — no `@Query` annotation needed. This intentionally returns **all** rows for the user, active or not, since a sold GOLD/BOND/COMMODITY holding may already be `isActive = false`.)

- [ ] **Step 2: Write the failing test for `LotTrackingService` type propagation**

In `LotTrackingServiceTest.java`, add the new mock and a `@BeforeEach`, and add a new test. Replace:

```java
    @Mock TransactionRepository transactionRepository;
    @InjectMocks LotTrackingService service;

    private static final String USER = "u";
```

with:

```java
    @Mock TransactionRepository transactionRepository;
    @Mock org.amit.finwise.investment.repository.InvestmentRepository investmentRepository;
    @InjectMocks LotTrackingService service;

    private static final String USER = "u";

    @BeforeEach
    void setUp() {
        when(investmentRepository.findByUserId(USER)).thenReturn(List.of());
    }
```

Add the import `org.junit.jupiter.api.BeforeEach;` to the import block. Then add this new test after `sellConsumesOldestLotsFirstAndSplitsAcrossLots`:

```java

    @Test
    void realizedGain_carriesInvestmentTypeFromInvestmentRecord() {
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(
                buy("GOLDBEES", "2023-01-10", 10, 50),
                sell("GOLDBEES", "2025-06-10", 10, 65)));
        when(investmentRepository.findByUserId(USER)).thenReturn(List.of(
                org.amit.finwise.investment.model.Investment.builder()
                        .userId(USER).type(org.amit.finwise.investment.enums.InvestmentType.GOLD)
                        .symbol("GOLDBEES").name("Gold BeES ETF")
                        .purchaseDate(LocalDate.parse("2023-01-10"))
                        .quantity(BigDecimal.TEN).costPerUnit(BigDecimal.valueOf(50))
                        .totalCost(BigDecimal.valueOf(500))
                        .build()));

        LotTrackingService.LotLedger ledger = service.buildLedger(USER);

        assertEquals(org.amit.finwise.investment.enums.InvestmentType.GOLD,
                ledger.realizedGains().getFirst().investmentType());
    }

    @Test
    void realizedGain_unknownSymbol_defaultsToStock() {
        when(transactionRepository.findBuySellTransactionsAsc(USER)).thenReturn(List.of(
                buy("TCS", "2023-01-10", 10, 100),
                sell("TCS", "2025-01-10", 10, 200)));

        LotTrackingService.LotLedger ledger = service.buildLedger(USER);

        assertEquals(org.amit.finwise.investment.enums.InvestmentType.STOCK,
                ledger.realizedGains().getFirst().investmentType());
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./mvnw test -Dtest=LotTrackingServiceTest`
Expected: FAIL to compile — `RealizedGain.investmentType()` doesn't exist, and `LotTrackingService` has no `InvestmentRepository` dependency yet.

- [ ] **Step 4: Add `InvestmentRepository` and the `investmentType` field to `LotTrackingService`**

Replace the field declarations and imports at the top of `LotTrackingService.java`:

```java
package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
```

with:

```java
package org.amit.finwise.investment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

Replace the class field:

```java
    private final TransactionRepository transactionRepository;
```

with:

```java
    private final TransactionRepository transactionRepository;
    private final InvestmentRepository investmentRepository;
```

Replace the `RealizedGain` record:

```java
    /** One SELL matched against one consumed BUY lot. */
    public record RealizedGain(
            String symbol,
            LocalDate buyDate,
            LocalDate sellDate,
            BigDecimal quantity,
            BigDecimal costPerUnit,
            BigDecimal sellPricePerUnit,
            boolean longTerm
    ) {
        public double gain() {
            return sellPricePerUnit.subtract(costPerUnit).multiply(quantity).doubleValue();
        }
    }
```

with:

```java
    /** One SELL matched against one consumed BUY lot. */
    public record RealizedGain(
            String symbol,
            LocalDate buyDate,
            LocalDate sellDate,
            BigDecimal quantity,
            BigDecimal costPerUnit,
            BigDecimal sellPricePerUnit,
            boolean longTerm,
            InvestmentType investmentType
    ) {
        public double gain() {
            return sellPricePerUnit.subtract(costPerUnit).multiply(quantity).doubleValue();
        }
    }
```

Replace the start of `buildLedger` and the SELL-matching loop:

```java
    public LotLedger buildLedger(String userId) {
        List<Transaction> txns = transactionRepository.findBuySellTransactionsAsc(userId);

        Map<String, Deque<MutableLot>> open = new LinkedHashMap<>();
        List<RealizedGain> realized = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (Transaction t : txns) {
            String sym = t.getSymbol().toUpperCase();
            BigDecimal qty = t.getQuantity();
            BigDecimal price = pricePerUnit(t);
            if (qty == null || qty.signum() <= 0 || price == null) {
                notes.add("LOT_SKIPPED: " + sym + " " + t.getTransactionDate()
                        + " " + t.getTransactionType() + " — missing quantity/price");
                continue;
            }

            if (t.getTransactionType() == Transaction.TransactionType.BUY) {
                open.computeIfAbsent(sym, _ -> new ArrayDeque<>())
                        .addLast(new MutableLot(t.getTransactionDate(), qty, price));
                continue;
            }

            // SELL: consume oldest lots first
            BigDecimal remaining = qty;
            Deque<MutableLot> lots = open.get(sym);
            while (remaining.signum() > 0 && lots != null && !lots.isEmpty()) {
                MutableLot lot = lots.peekFirst();
                BigDecimal consumed = lot.quantity.min(remaining);
                boolean longTerm = lot.buyDate.isBefore(t.getTransactionDate().minusYears(1));
                realized.add(new RealizedGain(sym, lot.buyDate, t.getTransactionDate(),
                        consumed, lot.costPerUnit, price, longTerm));
                lot.quantity = lot.quantity.subtract(consumed);
                remaining = remaining.subtract(consumed);
                if (lot.quantity.signum() <= 0) lots.removeFirst();
            }
            if (remaining.signum() > 0) {
                notes.add(String.format(
                        "UNMATCHED_SELL: %s %s — %s units sold without a recorded BUY (history starts mid-stream); gains for these units not computed",
                        sym, t.getTransactionDate(), remaining.stripTrailingZeros().toPlainString()));
            }
        }
```

with:

```java
    public LotLedger buildLedger(String userId) {
        List<Transaction> txns = transactionRepository.findBuySellTransactionsAsc(userId);
        Map<String, InvestmentType> typeBySymbol = investmentRepository.findByUserId(userId).stream()
                .filter(i -> i.getSymbol() != null)
                .collect(Collectors.toMap(i -> i.getSymbol().toUpperCase(), Investment::getType, (a, b) -> a));

        Map<String, Deque<MutableLot>> open = new LinkedHashMap<>();
        List<RealizedGain> realized = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (Transaction t : txns) {
            String sym = t.getSymbol().toUpperCase();
            BigDecimal qty = t.getQuantity();
            BigDecimal price = pricePerUnit(t);
            if (qty == null || qty.signum() <= 0 || price == null) {
                notes.add("LOT_SKIPPED: " + sym + " " + t.getTransactionDate()
                        + " " + t.getTransactionType() + " — missing quantity/price");
                continue;
            }

            if (t.getTransactionType() == Transaction.TransactionType.BUY) {
                open.computeIfAbsent(sym, _ -> new ArrayDeque<>())
                        .addLast(new MutableLot(t.getTransactionDate(), qty, price));
                continue;
            }

            // SELL: consume oldest lots first
            BigDecimal remaining = qty;
            Deque<MutableLot> lots = open.get(sym);
            InvestmentType invType = typeBySymbol.getOrDefault(sym, InvestmentType.STOCK);
            while (remaining.signum() > 0 && lots != null && !lots.isEmpty()) {
                MutableLot lot = lots.peekFirst();
                BigDecimal consumed = lot.quantity.min(remaining);
                boolean longTerm = lot.buyDate.isBefore(t.getTransactionDate().minusYears(1));
                realized.add(new RealizedGain(sym, lot.buyDate, t.getTransactionDate(),
                        consumed, lot.costPerUnit, price, longTerm, invType));
                lot.quantity = lot.quantity.subtract(consumed);
                remaining = remaining.subtract(consumed);
                if (lot.quantity.signum() <= 0) lots.removeFirst();
            }
            if (remaining.signum() > 0) {
                notes.add(String.format(
                        "UNMATCHED_SELL: %s %s — %s units sold without a recorded BUY (history starts mid-stream); gains for these units not computed",
                        sym, t.getTransactionDate(), remaining.stripTrailingZeros().toPlainString()));
            }
        }
```

(`InvestmentType.STOCK` as the unknown-symbol default preserves today's implicit behavior — every symbol previously flowed through the equity-shaped netting path regardless of type.)

- [ ] **Step 5: Fix the `CapitalGainsTaxServiceTest.realized(...)` helper for the new `RealizedGain` field**

In `CapitalGainsTaxServiceTest.java`, replace:

```java
    /** Builds a RealizedGain whose gain() equals the requested rupee amount. */
    private static LotTrackingService.RealizedGain realized(String sym, double gain,
                                                            boolean longTerm, String sellDate) {
        // qty 1, cost 0, sell price = gain → gain() = gain
        return new LotTrackingService.RealizedGain(sym,
                LocalDate.parse(sellDate).minusYears(longTerm ? 2 : 0).minusDays(longTerm ? 0 : 30),
                LocalDate.parse(sellDate),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(gain), longTerm);
    }
```

with:

```java
    /** Builds a RealizedGain whose gain() equals the requested rupee amount. */
    private static LotTrackingService.RealizedGain realized(String sym, double gain,
                                                            boolean longTerm, String sellDate) {
        return realized(sym, gain, longTerm, sellDate, InvestmentType.STOCK);
    }

    private static LotTrackingService.RealizedGain realized(String sym, double gain,
                                                            boolean longTerm, String sellDate,
                                                            InvestmentType type) {
        // qty 1, cost 0, sell price = gain → gain() = gain
        return new LotTrackingService.RealizedGain(sym,
                LocalDate.parse(sellDate).minusYears(longTerm ? 2 : 0).minusDays(longTerm ? 0 : 30),
                LocalDate.parse(sellDate),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(gain), longTerm, type);
    }
```

- [ ] **Step 6: Run to verify Steps 1-5 compile and pass**

Run: `./mvnw test -Dtest=LotTrackingServiceTest,CapitalGainsTaxServiceTest`
Expected: PASS — all existing + new `LotTrackingServiceTest` tests green. The realized-netting tests in `CapitalGainsTaxServiceTest` still pass unchanged (they use the 1-arg `realized(...)` overload, which now defaults to `STOCK`, matching prior behavior).

- [ ] **Step 7: Write the failing test for `realizedTax()` NON_EQUITY_FLAT handling**

Add to `CapitalGainsTaxServiceTest.java`, in the "Realized netting" section:

```java

    @Test
    void realizedTax_nonEquityFlatSale_usesFlatRateNotEquityNetting() {
        // Gold sold at a 30,000 gain. buyDate→sellDate is 29 months apart (> 24-month non-equity
        // LT threshold) even though the ledger's own longTerm flag (1-year equity rule) is false —
        // realizedTax() must use its own 24-month check for NON_EQUITY_FLAT, not the ledger's flag.
        when(lotTrackingService.buildLedger(USER)).thenReturn(new LotTrackingService.LotLedger(
                Map.of(), List.of(new LotTrackingService.RealizedGain(
                        "GOLDBEES",
                        LocalDate.parse("2023-01-01"), LocalDate.parse("2025-06-01"),
                        BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(30_000),
                        false, InvestmentType.GOLD)),
                List.of()));

        CapitalGainsTaxService.RealizedTaxSummary s =
                service.realizedTax(USER, LocalDate.parse("2025-06-15"));

        assertEquals(30_000.0, s.nonEquityFlatGains().doubleValue(), 1e-9);
        assertEquals(3_750.0, s.nonEquityFlatTax().doubleValue(), 1e-9, "30,000 × 12.5%, held > 24 months");
        assertEquals(0.0, s.taxableStcg().doubleValue(), 1e-9, "must not enter equity netting");
        assertEquals(0.0, s.taxableLtcg().doubleValue(), 1e-9, "must not enter equity netting");
    }
```

- [ ] **Step 8: Run test to verify it fails**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#realizedTax_nonEquityFlatSale_usesFlatRateNotEquityNetting`
Expected: FAIL to compile — `RealizedTaxSummary.nonEquityFlatGains()`/`.nonEquityFlatTax()` don't exist yet.

- [ ] **Step 9: Update `realizedTax()` and `RealizedTaxSummary`**

Replace the `realizedTax` method body:

```java
    public RealizedTaxSummary realizedTax(String userId, LocalDate asOf) {
        LocalDate fyStart = asOf.getMonthValue() >= 4
                ? LocalDate.of(asOf.getYear(), 4, 1)
                : LocalDate.of(asOf.getYear() - 1, 4, 1);
        LocalDate fyEnd = fyStart.plusYears(1).minusDays(1);

        LotTrackingService.LotLedger ledger = lotTrackingService.buildLedger(userId);
        List<String> notes = new ArrayList<>(ledger.notes());

        double st = 0;
        double lt = 0;
        for (LotTrackingService.RealizedGain g : ledger.realizedGains()) {
            if (g.sellDate().isBefore(fyStart) || g.sellDate().isAfter(fyEnd)) continue;
            if (g.longTerm()) lt += g.gain(); else st += g.gain();
        }
```

with:

```java
    public RealizedTaxSummary realizedTax(String userId, LocalDate asOf) {
        LocalDate fyStart = asOf.getMonthValue() >= 4
                ? LocalDate.of(asOf.getYear(), 4, 1)
                : LocalDate.of(asOf.getYear() - 1, 4, 1);
        LocalDate fyEnd = fyStart.plusYears(1).minusDays(1);

        LotTrackingService.LotLedger ledger = lotTrackingService.buildLedger(userId);
        List<String> notes = new ArrayList<>(ledger.notes());

        double st = 0;
        double lt = 0;
        double nonEquityFlatGains = 0;
        double nonEquityFlatTax = 0;
        for (LotTrackingService.RealizedGain g : ledger.realizedGains()) {
            if (g.sellDate().isBefore(fyStart) || g.sellDate().isAfter(fyEnd)) continue;

            if (isNonEquityFlat(g.investmentType())) {
                boolean ltNonEquity = g.buyDate().isBefore(g.sellDate().minusMonths(nonEquityLtMonths));
                double gain = g.gain();
                if (gain > 0) {
                    nonEquityFlatGains += gain;
                    nonEquityFlatTax += ltNonEquity ? gain * nonEquityLtcgRate : gain * slabRate;
                }
                continue;
            }

            if (g.longTerm()) lt += g.gain(); else st += g.gain();
        }
```

Replace the `return new RealizedTaxSummary(...)` call:

```java
        return new RealizedTaxSummary(
                fyStart, fyEnd,
                rupees(st), rupees(lt),
                rupees(taxableStcg), rupees(taxableLtcg),
                rupees(taxableStcg * stcgRate), rupees(taxableLtcg * ltcgRate),
                rupees(exemptionUsed), rupees(Math.max(0, ltcgExemption - positiveLtcg)),
                rupees(carryForwardStcl), rupees(carryForwardLtcl),
                List.copyOf(notes));
    }
```

with:

```java
        return new RealizedTaxSummary(
                fyStart, fyEnd,
                rupees(st), rupees(lt),
                rupees(taxableStcg), rupees(taxableLtcg),
                rupees(taxableStcg * stcgRate), rupees(taxableLtcg * ltcgRate),
                rupees(exemptionUsed), rupees(Math.max(0, ltcgExemption - positiveLtcg)),
                rupees(carryForwardStcl), rupees(carryForwardLtcl),
                List.copyOf(notes),
                rupees(nonEquityFlatGains), rupees(nonEquityFlatTax));
    }

    private static boolean isNonEquityFlat(InvestmentType type) {
        return type == InvestmentType.GOLD || type == InvestmentType.BOND || type == InvestmentType.COMMODITY;
    }
```

Replace the `RealizedTaxSummary` record:

```java
    public record RealizedTaxSummary(
            LocalDate fyStart,
            LocalDate fyEnd,
            BigDecimal realizedStcg,         // pre-netting aggregate (may be negative)
            BigDecimal realizedLtcg,         // pre-netting aggregate (may be negative)
            BigDecimal taxableStcg,
            BigDecimal taxableLtcg,          // after netting and exemption
            BigDecimal stcgTax,
            BigDecimal ltcgTax,
            BigDecimal exemptionUsed,
            BigDecimal exemptionHeadroom,    // ₹ of LTCG still realizable tax-free this FY
            BigDecimal carryForwardStcl,
            BigDecimal carryForwardLtcl,
            List<String> notes
    ) {}
```

with:

```java
    public record RealizedTaxSummary(
            LocalDate fyStart,
            LocalDate fyEnd,
            BigDecimal realizedStcg,         // pre-netting aggregate (may be negative)
            BigDecimal realizedLtcg,         // pre-netting aggregate (may be negative)
            BigDecimal taxableStcg,
            BigDecimal taxableLtcg,          // after netting and exemption
            BigDecimal stcgTax,
            BigDecimal ltcgTax,
            BigDecimal exemptionUsed,
            BigDecimal exemptionHeadroom,    // ₹ of LTCG still realizable tax-free this FY
            BigDecimal carryForwardStcl,
            BigDecimal carryForwardLtcl,
            List<String> notes,
            BigDecimal nonEquityFlatGains,   // GOLD/BOND/COMMODITY realized gains, flat rate
            BigDecimal nonEquityFlatTax
    ) {}
```

- [ ] **Step 10: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest,LotTrackingServiceTest`
Expected: PASS — all tests green, including the realized-path non-equity-flat test.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/amit/finwise/investment/repository/InvestmentRepository.java \
        src/main/java/org/amit/finwise/investment/service/LotTrackingService.java \
        src/main/java/org/amit/finwise/investment/service/CapitalGainsTaxService.java \
        src/test/java/org/amit/finwise/investment/service/LotTrackingServiceTest.java \
        src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "feat(tax): extend realizedTax() to handle NON_EQUITY_FLAT sales via lot ledger"
```

---

### Task 7: Mixed-portfolio integration test (final regression + cross-contamination check)

**Files:**
- Test: `src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java`

**Interfaces:**
- Consumes: everything produced by Tasks 1-6. No production code changes in this task.

- [ ] **Step 1: Write the integration test**

Add to `CapitalGainsTaxServiceTest.java`:

```java

    // ── Mixed-portfolio integration: regimes must not cross-contaminate ──────

    @Test
    void mixedPortfolio_allFiveRegimesComputeIndependently() {
        Investment stock = Investment.builder()
                .userId(USER).type(InvestmentType.STOCK).symbol("TCS").name("TCS")
                .purchaseDate(LocalDate.now().minusMonths(6))
                .unrealizedGainLoss(BigDecimal.valueOf(10_000))
                .build();
        Investment debtMf = Investment.builder()
                .userId(USER).type(InvestmentType.MUTUAL_FUND).name("ABC Corporate Bond Fund")
                .purchaseDate(LocalDate.now().minusYears(3))
                .unrealizedGainLoss(BigDecimal.valueOf(5_000))
                .build();
        Investment fd = Investment.builder()
                .userId(USER).type(InvestmentType.FIXED_DEPOSIT).name("SBI FD")
                .purchaseDate(LocalDate.now().minusYears(1))
                .quantity(BigDecimal.ONE).costPerUnit(BigDecimal.valueOf(200_000))
                .interestRate(BigDecimal.valueOf(6.5))
                .build();
        Investment gold = Investment.builder()
                .userId(USER).type(InvestmentType.GOLD).name("Physical Gold")
                .purchaseDate(LocalDate.now().minusMonths(30))
                .unrealizedGainLoss(BigDecimal.valueOf(20_000))
                .build();
        Investment ppf = Investment.builder()
                .userId(USER).type(InvestmentType.PPF).name("PPF")
                .purchaseDate(LocalDate.now().minusYears(5))
                .currentValue(BigDecimal.valueOf(300_000))
                .build();

        CapitalGainsTaxService.TaxEstimate est =
                service.estimate(List.of(stock, debtMf, fd, gold, ppf));

        assertEquals(10_000.0, est.stcgGains().doubleValue(), 1e-9, "equity STCG untouched by other regimes");
        assertEquals(5_000.0, est.slabGains().doubleValue(), 1e-9, "debt MF slab gain untouched");
        assertEquals(13_000.0, est.interestIncomeGains().doubleValue(), 1e-9, "200,000 × 6.5%");
        assertEquals(20_000.0, est.nonEquityFlatGains().doubleValue(), 1e-9, "gold gain untouched");
        assertEquals(300_000.0, est.exemptAmount().doubleValue(), 1e-9, "PPF fully exempt");

        // Total tax must be the simple sum of each regime's own tax — no cross-netting.
        double expectedTotal = 2_000.0            // 10,000 × 20% equity STCG
                + 1_500.0                          // 5,000 × 30% slab
                + (13_000.0 * 0.30)                // interest income × slab
                + 2_500.0;                         // 20,000 × 12.5% non-equity flat LTCG
        assertEquals(expectedTotal, est.totalTaxIfSoldToday().doubleValue(), 1e-6);
        assertTrue(est.exclusions().isEmpty());
    }
```

- [ ] **Step 2: Run to verify it fails first (sanity), then run full suite**

Run: `./mvnw test -Dtest=CapitalGainsTaxServiceTest#mixedPortfolio_allFiveRegimesComputeIndependently`
Expected: this should already PASS if Tasks 1-6 were implemented correctly — this task is a regression/integration checkpoint, not new behavior. If it fails, the failure pinpoints exactly which regime's bucket leaked into another; fix the specific `estimate()` branch identified before proceeding.

- [ ] **Step 3: Run the entire investment module test suite**

Run: `./mvnw test -Dtest=org.amit.finwise.investment.**`
Expected: PASS — every test in `investment/service` and related packages green.

- [ ] **Step 4: Run the full project build**

Run: `./mvnw clean package -DskipTests` then `./mvnw test`
Expected: BUILD SUCCESS; full suite green (no regressions introduced in unrelated modules, since all changes in this plan are confined to `investment/`).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/org/amit/finwise/investment/service/CapitalGainsTaxServiceTest.java
git commit -m "test(tax): add mixed-portfolio integration test across all 5 regimes"
```

---

## Post-Plan Note (for whoever deploys this)

`TaxHarvestingService` and `InvestmentService` only ever call `capitalGainsTaxService.estimate()`, `.realizedTax()`, `.stcgRate()`, and `.ltcgRate()` — none of which changed signature. No changes are needed in those two files. Verify this holds by re-running `grep -rn "capitalGainsTaxService\." src/main/java` after Task 7 and confirming the call sites listed still match `InvestmentService.java:99` and `TaxHarvestingService.java:46,88,98` with the same method names.
