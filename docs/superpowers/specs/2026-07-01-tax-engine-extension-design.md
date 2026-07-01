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
| `NON_EQUITY_FLAT` *(new)* | GOLD, BOND (on sale), COMMODITY | 24-month LT threshold (not 12). LTCG is a flat, admin-configurable rate with **no indexation** — the indexation-choice carve-out from the August 2024 Finance Act amendment applies only to land/building (REAL_ESTATE), not movable assets, and REAL_ESTATE is deferred to a later spec. STCG (≤24 months) taxed at slab rate |
| `EXEMPT` *(new)* | PPF (always); INSURANCE_POLICY (if premium/sum-assured ratio within threshold); GOLD matching a Sovereign-Gold-Bond name pattern held to 8-year maturity | Zero tax, still shown in the report (not silently dropped) |
| `EXCLUDED` | CRYPTOCURRENCY, REAL_ESTATE, OTHER | Unchanged — deferred |

**SGB detection** mirrors the existing `DEBT_FUND_NAME` regex pattern used for MF classification: a new `SGB_NAME` pattern (`sovereign gold bond|sgb`) checked against `Investment.name` before falling through to general `GOLD` flat-rate treatment.

---

## 5. Computation Logic

### 5.1 Interest income (FD, post-office, bond coupon)
`annualInterest = costPerUnit × quantity × interestRate`. Tax = `annualInterest × slabRate`. This is a simple-annual-accrual approximation, not exact bank compounding schedules — disclosed via `INTEREST_SIMPLE_ANNUAL_ASSUMED` note.

TDS: banks deduct 10% once interest from the same payer crosses ₹40,000/₹50,000 per FY. Best-effort group-by on the existing `platform` field (proxy for "same bank/institution"); reported as **informational only** in `notes` since interest paid outside Finwise-tracked accounts isn't visible.

### 5.2 Non-equity flat-rate capital assets (GOLD/BOND/COMMODITY on sale)

**Correction from the original spec draft:** the August 2024 Finance Act amendment's indexation-choice carve-out (12.5% without indexation OR 20% with indexation) applies **only to land/building (REAL_ESTATE)**, not to movable/financial assets. Since REAL_ESTATE is deferred to a later spec, this spec needs **no Cost Inflation Index lookup and no dual computation at all** for GOLD, BOND, or COMMODITY — this is materially simpler than the original draft.

- **LT threshold:** 24 months (not 12).
- **LTCG (>24 months):** flat rate on `(saleValue - cost)`, no indexation. Rate is **admin-configurable** via `cfo.tax.non-equity-ltcg-rate` (default `0.125`), following the exact pattern of the existing `cfo.tax.ltcg-rate` property — so the rate can be updated without a code change if the law changes again.
- **STCG (≤24 months):** taxed at the existing configurable slab rate (`cfo.tax.slab-rate`), same as debt MF.
- The 24-month threshold itself is also admin-configurable via `cfo.tax.non-equity-lt-months` (default `24`), for the same reason.

No new service, no properties/YAML table, no startup-warning mechanism needed for this section — those were entirely a byproduct of the incorrect indexation-choice assumption and are removed from scope.

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
- `nonEquityFlatGains`, `nonEquityFlatTax` (covers GOLD/BOND/COMMODITY, both LT and ST buckets)
- `exemptAmount` (informational, zero tax)

`RealizedTaxSummary` gains the equivalent fields for the realized-gains path.

All existing EQUITY/DEBT_SLAB fields and behavior are unchanged — purely additive, non-breaking for current API consumers.

---

## 7. Error Handling & Disclosure Conventions

Follows the existing convention exactly: missing data never throws. It degrades to a `notes` entry describing the assumption made — e.g. `INTEREST_RATE_MISSING: {name} — no interestRate on record, excluded from interest-income estimate` — and the holding still appears in the estimate rather than silently vanishing. No new exception types.

---

## 8. Testing Plan

New/extended test coverage in `CapitalGainsTaxServiceTest` (split into a separate test class if it grows unwieldy):

- 24-month LT boundary for non-equity flat-rate assets (23 vs 24 vs 25 months held)
- Non-equity LTCG uses the configurable flat rate correctly (no indexation applied anywhere)
- Insurance threshold boundary — exactly at 10%/20%, just above, just below
- SGB name-detection heuristic (positive and negative matches)
- PPF full exemption
- Interest-income computation with a missing `interestRate` degrading gracefully (holding still shown, tax excluded, note present)
- Mixed-portfolio integration test asserting the five regimes never cross-contaminate — specifically, an `INTEREST_INCOME` holding must never be netted against a capital loss from any other regime

---

## 9. Out of Scope / Deferred

- CRYPTOCURRENCY (flat 30%, Section 115BBH, no loss offset whatsoever) — separate spec
- REAL_ESTATE (Section 54/54EC reinvestment exemptions, TDS 194-IA) — separate spec. This is also where the genuine indexation-choice mechanism (12.5% no-indexation vs 20%-with-CII-indexation, pre-23-Jul-2024 acquisitions) belongs, since that carve-out is real-estate-specific under the August 2024 amendment
- Exact bank-compounding interest schedules (quarterly/monthly) — simple annual accrual only
- ULIP-specific Section 112A capital-gains treatment — simplified to slab rate with a disclosure note
- Cross-account TDS aggregation (interest earned at institutions outside Finwise-tracked platforms is invisible to this system)
