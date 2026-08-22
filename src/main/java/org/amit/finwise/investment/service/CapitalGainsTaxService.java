package org.amit.finwise.investment.service;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Estimates Indian capital gains tax — both on unrealized equity gains (so P&L
 * can be shown net of tax) and on realized gains within a financial year (with
 * statutory loss netting).
 *
 * Equity / equity-oriented funds (Sec 111A / 112A, post-Budget-2024 regime):
 *   - STCG (held ≤ 1 year): flat rate on gains (default 20%)
 *   - LTCG (held > 1 year): rate on gains above an annual exemption
 *     (default 12.5% above ₹1.25 lakh), exemption applied portfolio-wide
 *   - §55(2)(ac) grandfathering: equity bought on/before 31-Jan-2018 steps the
 *     cost up to max(actual cost, min(FMV on 31-Jan-2018, sale price)). FMV is
 *     proxied by the adjusted close on the nearest trading day — adjusted (not
 *     raw) so it is comparable with broker-reported post-split average prices.
 *
 * Debt mutual funds purchased after 1-Apr-2023 are taxed at the investor's slab
 * rate regardless of holding period; fund category is inferred from the scheme
 * name (a note flags the assumption).
 *
 * Realized-gain netting (set-off rules): STCL offsets STCG first then LTCG;
 * LTCL offsets LTCG only. Unabsorbed losses carry forward up to 8 assessment
 * years (reported informationally — prior-year carry-ins are outside this
 * system's view).
 *
 * Unrealized losses are NOT netted in {@link #estimate} — that estimate stays a
 * conservative upper bound on tax if everything were sold today.
 */
@Slf4j
@Service
public class CapitalGainsTaxService {

    private final StockPriceService stockPriceService;
    private final LotTrackingService lotTrackingService;
    private final double stcgRate;
    private final double ltcgRate;
    private final double ltcgExemption;
    private final double slabRate;
    private final LocalDate grandfatheringDate;
    private final double tdsThreshold;
    private final double insuranceExemptThresholdPost2012;
    private final double insuranceExemptThresholdPre2012;
    private final LocalDate insuranceExemptCutoffDate;

    private static final Pattern DEBT_FUND_NAME = Pattern.compile(
            "debt|liquid|gilt|g-sec|bond|overnight|money market|ultra short|low duration|"
            + "short duration|medium duration|long duration|corporate bond|credit risk|"
            + "banking (&|and) psu|floater|floating rate|treasury|fmp|fixed maturity",
            Pattern.CASE_INSENSITIVE);

    public CapitalGainsTaxService(
            StockPriceService stockPriceService,
            LotTrackingService lotTrackingService,
            @Value("${cfo.tax.stcg-rate:0.20}") double stcgRate,
            @Value("${cfo.tax.ltcg-rate:0.125}") double ltcgRate,
            @Value("${cfo.tax.ltcg-exemption:125000}") double ltcgExemption,
            @Value("${cfo.tax.slab-rate:0.30}") double slabRate,
            @Value("${cfo.tax.grandfathering-date:2018-01-31}") String grandfatheringDate,
            @Value("${cfo.tax.tds-threshold:40000}") double tdsThreshold,
            @Value("${cfo.tax.insurance-exempt-threshold-post-2012:0.10}") double insuranceExemptThresholdPost2012,
            @Value("${cfo.tax.insurance-exempt-threshold-pre-2012:0.20}") double insuranceExemptThresholdPre2012,
            @Value("${cfo.tax.insurance-exempt-cutoff-date:2012-04-01}") String insuranceExemptCutoffDate) {
        this.stockPriceService = stockPriceService;
        this.lotTrackingService = lotTrackingService;
        this.stcgRate = stcgRate;
        this.ltcgRate = ltcgRate;
        this.ltcgExemption = ltcgExemption;
        this.slabRate = slabRate;
        this.grandfatheringDate = LocalDate.parse(grandfatheringDate);
        this.tdsThreshold = tdsThreshold;
        this.insuranceExemptThresholdPost2012 = insuranceExemptThresholdPost2012;
        this.insuranceExemptThresholdPre2012 = insuranceExemptThresholdPre2012;
        this.insuranceExemptCutoffDate = LocalDate.parse(insuranceExemptCutoffDate);
    }

    // ── Unrealized estimate ──────────────────────────────────────────────────

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
                BigDecimal value = inv.getCurrentValue();
                if (value == null) {
                    value = inv.getTotalCost() != null ? inv.getTotalCost() : BigDecimal.ZERO;
                    notes.add("EXEMPT_VALUE_ASSUMED_FROM_COST: " + inv.getName()
                            + " — no currentValue on record, exempt amount estimated from totalCost");
                }
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
            if (e.getValue() > tdsThreshold) {
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

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * Gain with the §55(2)(ac) cost step-up where applicable; falls back to the
     * broker-reported unrealized gain when FMV or per-unit figures are missing.
     */
    private double grandfatheredGain(Investment inv, List<String> notes) {
        double rawGain = inv.getUnrealizedGainLoss().doubleValue();
        if (inv.getPurchaseDate().isAfter(grandfatheringDate)) return rawGain;
        if (inv.getSymbol() == null || inv.getQuantity() == null
                || inv.getCostPerUnit() == null || inv.getCurrentPrice() == null) {
            notes.add("GRANDFATHERING_SKIPPED: " + (inv.getSymbol() != null ? inv.getSymbol() : inv.getName())
                    + " — pre-2018 holding but per-unit figures missing; raw gain used (tax may be overstated)");
            return rawGain;
        }

        Optional<BigDecimal> fmvOpt = stockPriceService.closeOn(inv.getSymbol().toUpperCase(), grandfatheringDate);
        if (fmvOpt.isEmpty()) {
            notes.add("GRANDFATHERING_SKIPPED: " + inv.getSymbol()
                    + " — FMV on " + grandfatheringDate + " unavailable; raw gain used (tax may be overstated)");
            return rawGain;
        }

        double fmv = fmvOpt.get().doubleValue();
        double cost = inv.getCostPerUnit().doubleValue();
        double current = inv.getCurrentPrice().doubleValue();
        double steppedUpCost = Math.max(cost, Math.min(fmv, current));
        if (steppedUpCost > cost) {
            notes.add(String.format("GRANDFATHERED: %s cost stepped up ₹%.2f → ₹%.2f using 31-Jan-2018 FMV ₹%.2f (§55(2)(ac))",
                    inv.getSymbol(), cost, steppedUpCost, fmv));
        }
        return (current - steppedUpCost) * inv.getQuantity().doubleValue();
    }

    // ── Realized gains with statutory netting ────────────────────────────────

    /**
     * Realized capital gains for the financial year containing {@code asOf},
     * from the FIFO lot ledger, with intra-/inter-head loss set-off applied.
     * Covers symbol-bearing (equity) transactions only.
     */
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

        // Set-off: STCL against STCG happens implicitly in the aggregate; the
        // remaining STCL then offsets LTCG. LTCL offsets LTCG only.
        double netSt = st;
        double netLt = lt;
        if (netSt < 0 && netLt > 0) {
            double offset = Math.min(-netSt, netLt);
            netLt -= offset;
            netSt += offset;
        }
        double carryForwardStcl = netSt < 0 ? -netSt : 0;
        double carryForwardLtcl = netLt < 0 ? -netLt : 0;
        if (carryForwardStcl > 0 || carryForwardLtcl > 0) {
            notes.add(String.format(
                    "CARRY_FORWARD: unabsorbed losses (STCL ₹%.0f, LTCL ₹%.0f) can be carried forward up to 8 assessment years if the return is filed on time",
                    carryForwardStcl, carryForwardLtcl));
        }

        double taxableStcg = Math.max(0, netSt);
        double positiveLtcg = Math.max(0, netLt);
        double exemptionUsed = Math.min(positiveLtcg, ltcgExemption);
        double taxableLtcg = Math.max(0, positiveLtcg - ltcgExemption);

        return new RealizedTaxSummary(
                fyStart, fyEnd,
                rupees(st), rupees(lt),
                rupees(taxableStcg), rupees(taxableLtcg),
                rupees(taxableStcg * stcgRate), rupees(taxableLtcg * ltcgRate),
                rupees(exemptionUsed), rupees(Math.max(0, ltcgExemption - positiveLtcg)),
                rupees(carryForwardStcl), rupees(carryForwardLtcl),
                List.copyOf(notes));
    }

    // ── Regime classification ────────────────────────────────────────────────

    private enum Regime { EQUITY, DEBT_SLAB, INTEREST_INCOME, NON_EQUITY_FLAT, EXEMPT, EXCLUDED }

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

        if (type == InvestmentType.FIXED_DEPOSIT || type == InvestmentType.POST_OFFICE_SCHEME) {
            return Regime.INTEREST_INCOME;
        }

        if (type == InvestmentType.INSURANCE_POLICY) {
            if (inv.getAnnualPremium() == null || inv.getSumAssured() == null
                    || inv.getSumAssured().signum() == 0) {
                notes.add("INSURANCE_ASSUMED_EXEMPT_10_10D: " + inv.getName()
                        + " — premium/sum-assured not on record; exemption assumed "
                        + "(cannot verify the Section 10(10D) threshold)");
                return Regime.EXEMPT;
            }
            boolean postApril2012 = inv.getPurchaseDate() != null
                    && !inv.getPurchaseDate().isBefore(insuranceExemptCutoffDate);
            double threshold = postApril2012 ? insuranceExemptThresholdPost2012 : insuranceExemptThresholdPre2012;
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

        return Regime.EXCLUDED;
    }

    double stcgRate()      { return stcgRate; }
    double ltcgRate()      { return ltcgRate; }
    double ltcgExemption() { return ltcgExemption; }

    private BigDecimal rupees(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

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

    public record HoldingTax(
            String symbol,
            LocalDate purchaseDate,
            String bucket,                   // STCG | LTCG | SLAB
            BigDecimal unrealizedGainLoss
    ) {}

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
}
