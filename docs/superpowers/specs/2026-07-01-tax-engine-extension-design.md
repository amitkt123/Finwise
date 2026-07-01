# Tax Engine Extension (9 Remaining Asset Types) — Design Spec
**Date:** 2026-07-01
**Status:** Approved

---

## 1. Problem Statement

`CapitalGainsTaxService` already computes Indian capital gains tax correctly for STOCK, ETF, and MUTUAL_FUND — FIFO lot tracking, 2018 grandfathering, debt-vs-equity fund classification by name, statutory loss set-off, and 8-year carry-forward. It excludes the other 9 of the 13 tracked `InvestmentType` values entirely: BOND, CRYPTOCURRENCY, REAL_ESTATE, GOLD, COMMODITY, FIXED_DEPOSIT, POST_OFFICE_SCHEME, PPF, INSURANCE_POLICY (`regimeOf()` returns `EXCLUDED` for all of them today).

This spec extends coverage to **7 of those 9 types**: PPF, FIXED_DEPOSIT, POST_OFFICE_SCHEME, INSURANCE_POLICY, GOLD, BOND, COMMODITY. CRYPTOCURRENCY and REAL_ESTATE are deliberately deferred to a separate future spec — both use tax regimes (flat 30% with no loss offset; Section 54 reinvestment exemptions) that don't fit the existing netting/set-off model at all and deserve isolated design and testing rather than being bolted on here.

Without this, users holding FDs, PPF, insurance, gold, bonds, or commodities get an incomplete tax picture — those holdings simply vanish from every tax estimate today.

---

## 2. Scope

- **In scope:** PPF, FIXED_DEPOSIT, POST_OFFICE_SCHEME, INSURANCE_POLICY, GOLD, BOND, COMMODITY
- **Out of scope (future spec):** CRYPTOCURRENCY, REAL_ESTATE, OTHER
- **Schema:** additive, nullable columns only (Hibernate `ddl-auto=update` handles it — no manual migration)
- **No breaking changes** to existing EQUITY/DEBT_SLAB behavior, `TaxEstimate`/`RealizedTaxSummary` callers, or the public method signatures of `CapitalGainsTaxService`

---

## 3. Schema Changes

Four new **nullable** fields on `Investment`:

| Field | Type | Purpose |
|---|---|---|
| `interestRate` | `BigDecimal` (annual %, e.g. `7.1000`) | Interest accrual for FIXED_DEPOSIT, POST_OFFICE_SCHEME; coupon income for BOND |
| `maturityDate` | `LocalDate` | FIXED_DEPOSIT, POST_OFFICE_SCHEME, BOND, INSURANCE_POLICY |
| `sumAssured` | `BigDecimal` | INSURANCE_POLICY — Section 10(10D) exemption test |
| `annualPremium` | `BigDecimal` | INSURANCE_POLICY — Section 10(10D) exemption test |

`AddInvestmentRequest` and `InvestmentResponse` DTOs gain the same four fields as optional inputs/outputs. Existing rows have nulls; any computation needing a missing field degrades to a disclosed assumption via the `notes` list (see §7), never an error.

---

## 4. Regime Model Extension

Current `Regime` enum: `EQUITY | SLAB | EXCLUDED`. Extended to five values — interest income and capital gains are legally distinct tax *heads* in India and must not share a netting bucket:

| Regime | Types | Treatment |
|---|---|---|
| `EQUITY` | STOCK, ETF, equity MF | Unchanged |
| `DEBT_SLAB` | Debt MF | Unchanged (renamed from `SLAB` for clarity) — still a capital gain, taxed at slab rate |
| `INTEREST_INCOME` *(new)* | FIXED_DEPOSIT, POST_OFFICE_SCHEME, BOND (coupon) | Annual interest taxed at slab rate as "Income from Other Sources" — never nets against capital losses |
| `INDEXED_CHOICE` *(new)* | GOLD, BOND (on sale), COMMODITY | 24-month LT threshold (not 12). Pre-23-Jul-2024 acquisitions get computed both ways (12.5% no indexation vs 20% with CII indexation) and the lower-tax option is reported |
| `EXEMPT` *(new)* | PPF (always); INSURANCE_POLICY (if premium/sum-assured ratio within threshold); GOLD matching a Sovereign-Gold-Bond name pattern held to 8-year maturity | Zero tax, still shown in the report (not silently dropped) |
| `EXCLUDED` | CRYPTOCURRENCY, REAL_ESTATE, OTHER | Unchanged — deferred |

**SGB detection** mirrors the existing `DEBT_FUND_NAME` regex pattern used for MF classification: a new `SGB_NAME` pattern (`sovereign gold bond|sgb`) checked against `Investment.name` before falling through to general `GOLD` indexed-choice treatment.

---

## 5. Computation Logic

### 5.1 Interest income (FD, post-office, bond coupon)
`annualInterest = costPerUnit × quantity × interestRate`. Tax = `annualInterest × slabRate`. This is a simple-annual-accrual approximation, not exact bank compounding schedules — disclosed via `INTEREST_SIMPLE_ANNUAL_ASSUMED` note.

TDS: banks deduct 10% once interest from the same payer crosses ₹40,000/₹50,000 per FY. Best-effort group-by on the existing `platform` field (proxy for "same bank/institution"); reported as **informational only** in `notes` since interest paid outside Finwise-tracked accounts isn't visible.

### 5.2 Indexed-choice capital assets (GOLD/BOND/COMMODITY on sale)
Requires a Cost Inflation Index (CII) lookup by financial year. New `CostInflationIndexService`, backed by a properties/YAML file (one `FY → CII` entry per line), **not hardcoded in Java** — this table must be updated whenever CBDT notifies the new year's CII (same operational pattern as the existing `@Value`-externalized tax rates). The service raises a startup warning (not a hard failure) if the current FY's entry is missing, so a stale table fails loud rather than silently mis-taxing. The initial checked-in file ships with a placeholder for the current FY that must be populated with the real CBDT-published value before this goes live — it is **not** to be guessed at implementation time.

For each holding, compute both:
- No-indexation: `(saleValue - cost) × 12.5%`
- With indexation: `(saleValue - cost × CII_sale/CII_purchase) × 20%`

...and report the lower of the two (taxpayer's legal right to choose), following the same pattern as the existing grandfathering logic. Only applies to acquisitions before 23-Jul-2024; later acquisitions get no-indexation-only at 12.5%.

### 5.3 Insurance (Section 10(10D) test)
`ratio = annualPremium / sumAssured`. Threshold: 10% if `purchaseDate >= 2012-04-01`, else 20% (pre-2012 grandfathered rule — same date-threshold pattern as existing grandfathering).

- Ratio within threshold → `EXEMPT`, noted.
- Ratio exceeds threshold → gain (`currentValue - premiums paid to date`) taxed at slab rate. Note discloses that ULIP-specific Section 112A treatment (equity-oriented ULIPs with premium >₹2.5L/yr) is simplified to slab-rate here, since no policy sub-type is tracked.

### 5.4 PPF
Always `EXEMPT`. Zero computation. Included in the report (not dropped) so the estimate reads as complete.

---

## 6. Output Shape Changes

`TaxEstimate` gains:
- `interestIncomeGains`, `interestIncomeTax`
- `indexedChoiceGains`, `indexedChoiceTaxNoIndexation`, `indexedChoiceTaxWithIndexation` (both reported, plus which was selected)
- `exemptAmount` (informational, zero tax)

`RealizedTaxSummary` gains the equivalent fields for the realized-gains path.

All existing EQUITY/DEBT_SLAB fields and behavior are unchanged — purely additive, non-breaking for current API consumers.

---

## 7. Error Handling & Disclosure Conventions

Follows the existing convention exactly: missing data never throws. It degrades to a `notes` entry describing the assumption made — e.g. `INTEREST_RATE_MISSING: {name} — no interestRate on record, excluded from interest-income estimate` — and the holding still appears in the estimate rather than silently vanishing. No new exception types.

---

## 8. Testing Plan

New/extended test coverage in `CapitalGainsTaxServiceTest` (split into a separate test class if it grows unwieldy):

- 24-month LT boundary for indexed-choice assets (23 vs 24 vs 25 months held)
- Indexation-vs-no-indexation selects the genuinely lower tax in both directions (test a case where each wins)
- Insurance threshold boundary — exactly at 10%/20%, just above, just below
- SGB name-detection heuristic (positive and negative matches)
- PPF full exemption
- Interest-income computation with a missing `interestRate` degrading gracefully (holding still shown, tax excluded, note present)
- Mixed-portfolio integration test asserting the five regimes never cross-contaminate — specifically, an `INTEREST_INCOME` holding must never be netted against a capital loss from any other regime
- CII table missing current-FY entry triggers the startup warning path (not a crash)

---

## 9. Out of Scope / Deferred

- CRYPTOCURRENCY (flat 30%, Section 115BBH, no loss offset whatsoever) — separate spec
- REAL_ESTATE (Section 54/54EC reinvestment exemptions, TDS 194-IA) — separate spec
- Exact bank-compounding interest schedules (quarterly/monthly) — simple annual accrual only
- ULIP-specific Section 112A capital-gains treatment — simplified to slab rate with a disclosure note
- Cross-account TDS aggregation (interest earned at institutions outside Finwise-tracked platforms is invisible to this system)
