package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.LookThroughResult;
import org.amit.finwise.cfo.model.MfPortfolioHolding;
import org.amit.finwise.cfo.repository.MfPortfolioHoldingRepository;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.amit.finwise.marketdata.model.MfNav;
import org.amit.finwise.marketdata.repository.MfNavRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Mutual-fund look-through (Phase 12a).
 *
 * Explodes each {@link InvestmentType#MUTUAL_FUND} position into its disclosed
 * constituents and folds them into the directly-held equity book to produce TRUE
 * effective per-stock and per-sector exposures, plus the effective HHIs.
 *
 * For a fund position the investment's {@code symbol} is treated as the AMFI scheme
 * code; the latest disclosure on file (via {@link MfPortfolioHoldingRepository}) is
 * used and its {@code asOf} stamped. The fund weight not covered by disclosed
 * constituents is tracked as {@code unmappedResidue} — never dropped, never spread.
 *
 * The effective numbers are only marked {@code feedsModel} (safe for effective HHIs
 * and the P8 factor model to consume) when MF coverage ≥ {@value #COVERAGE_GATE}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LookThroughService {

    private final InvestmentRepository investmentRepository;
    private final MfPortfolioHoldingRepository mfHoldingRepo;
    private final SymbolExtractorService symbolExtractorService;
    private final MfNavRepository mfNavRepo;

    /** MF coverage at or above which effective exposures feed downstream models. */
    static final double COVERAGE_GATE = 0.70;
    /** Disclosure older than this many days earns a staleness note. */
    static final long STALE_DAYS = 120;

    public Optional<LookThroughResult> compute(String userId) {
        List<Investment> active = investmentRepository.findActiveInvestments(userId).stream()
                .filter(inv -> inv.getCurrentValue() != null
                        && inv.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (active.isEmpty()) return Optional.empty();

        double total = active.stream().mapToDouble(i -> i.getCurrentValue().doubleValue()).sum();
        if (total <= 0) return Optional.empty();

        List<String> notes = new ArrayList<>();
        Map<String, Double> directWeights = new LinkedHashMap<>();
        Map<String, Double> fundContributed = new LinkedHashMap<>();
        Map<String, String> sectorOf = new HashMap<>();          // symbol → resolved sector
        Map<String, LocalDate> asOfByScheme = new LinkedHashMap<>();

        double mfWeight = 0.0;          // total portfolio weight sitting in MFs
        double seenThrough = 0.0;       // MF weight whose constituents are disclosed

        for (Investment inv : active) {
            double w = inv.getCurrentValue().doubleValue() / total;

            if (inv.getType() == InvestmentType.MUTUAL_FUND) {
                mfWeight += w;
                String scheme = inv.getSymbol() != null ? inv.getSymbol().trim() : null;
                if (scheme == null || scheme.isBlank()) {
                    notes.add("MF_UNMAPPED: '" + inv.getName() + "' has no scheme code — "
                            + "its " + pct(w) + " is unmapped residue");
                    continue;
                }
                LocalDate asOf = mfHoldingRepo.latestAsOf(scheme);
                if (asOf == null) {
                    notes.add("MF_NO_DISCLOSURE: scheme " + scheme + " ('" + inv.getName()
                            + "') has no imported portfolio — " + pct(w) + " is unmapped residue");
                    continue;
                }
                asOfByScheme.put(scheme, asOf);
                long age = ChronoUnit.DAYS.between(asOf, LocalDate.now());
                if (age > STALE_DAYS) {
                    notes.add("MF_STALE: scheme " + scheme + " disclosure is " + age
                            + "d old (asOf " + asOf + ")");
                }
                // NAV-weighted drift (DF-4): how far the fund's NAV has moved since the
                // disclosure date. The disclosed composition is a snapshot; a large NAV
                // move means the true weights have drifted from what we explode below.
                navDriftSinceDisclosure(scheme, asOf).ifPresent(drift -> notes.add(String.format(
                        "MF_NAV_DRIFT: scheme %s NAV moved %+.1f%% since disclosure (asOf %s) — "
                                + "exploded weights are pre-drift", scheme, drift * 100, asOf)));

                List<MfPortfolioHolding> constituents =
                        mfHoldingRepo.findBySchemeCodeAndAsOf(scheme, asOf);
                double disclosedFraction = 0.0;
                for (MfPortfolioHolding h : constituents) {
                    String sym = h.getSymbol() != null ? h.getSymbol().toUpperCase()
                            : h.getIsin();              // fall back to ISIN as the key
                    if (sym == null) continue;
                    double frac = h.getWeightPct() / 100.0;     // within-fund fraction
                    if (frac <= 0) continue;
                    disclosedFraction += frac;
                    fundContributed.merge(sym, w * frac, Double::sum);
                    resolveSector(sym, h.getSector()).ifPresent(s -> sectorOf.put(sym, s));
                }
                // Disclosed weights can drift slightly past/under 100% — clamp the
                // seen-through fraction so residue never goes negative.
                double covered = Math.min(1.0, Math.max(0.0, disclosedFraction));
                seenThrough += w * covered;
                continue;
            }

            // Direct equity-like exposure with a resolvable symbol.
            if (inv.getType() == InvestmentType.STOCK || inv.getType() == InvestmentType.ETF) {
                String sym = inv.getSymbol() != null ? inv.getSymbol().toUpperCase() : null;
                if (sym == null || sym.isBlank()) continue;
                directWeights.merge(sym, w, Double::sum);
                resolveSector(sym, inv.getSector()).ifPresent(s -> sectorOf.put(sym, s));
            }
            // Other asset types (bonds, gold, FDs…) are out of equity look-through scope.
        }

        // ── Effective per-stock weights = direct + fund-contributed ───────────────
        Map<String, Double> effective = new LinkedHashMap<>();
        directWeights.forEach((sym, w) -> effective.merge(sym, w, Double::sum));
        fundContributed.forEach((sym, w) -> effective.merge(sym, w, Double::sum));

        double unmappedResidue = Math.max(0.0, mfWeight - seenThrough);
        double mfCoverage = mfWeight > 0 ? seenThrough / mfWeight : 1.0;
        boolean feedsModel = mfCoverage >= COVERAGE_GATE;

        // ── Effective sector weights ──────────────────────────────────────────────
        Map<String, Double> sectorEffective = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : effective.entrySet()) {
            String sector = sectorOf.getOrDefault(e.getKey(), "UNKNOWN");
            sectorEffective.merge(sector, e.getValue(), Double::sum);
        }

        // ── HHIs: direct (stocks only) vs effective (post look-through) ────────────
        double directNameHHI = hhi(directWeights.values());
        double effectiveNameHHI = hhi(effective.values());
        double directSectorHHI = hhi(sectorWeights(directWeights, sectorOf).values());
        double effectiveSectorHHI = hhi(sectorEffective.values());

        if (mfWeight > 0) {
            notes.add(String.format("MF_COVERAGE: %.0f%% of fund value seen through "
                            + "(%s effective stock exposure revealed; %s unmapped residue)",
                    mfCoverage * 100, pct(seenThrough), pct(unmappedResidue)));
            if (!feedsModel) {
                notes.add(String.format("LOOK_THROUGH_NOTE_ONLY: MF coverage %.0f%% < %.0f%% — "
                                + "effective HHIs/factor feed suppressed, narrative only",
                        mfCoverage * 100, COVERAGE_GATE * 100));
            }
        }

        String topName = effective.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + " " + pct(e.getValue())).orElse("n/a");
        String headline = String.format(
                "Look-through over %s of mutual funds: top effective name %s; "
                        + "effective name HHI %.3f vs direct %.3f%s",
                pct(mfWeight), topName, effectiveNameHHI, directNameHHI,
                feedsModel ? "" : " (note-only)");

        log.info("[LookThrough] mfWeight={}, coverage={}%, residue={}, feedsModel={}, names={}",
                pct(mfWeight), String.format("%.0f", mfCoverage * 100), pct(unmappedResidue),
                feedsModel, effective.size());

        return Optional.of(new LookThroughResult(
                asOfByScheme,
                Collections.unmodifiableMap(directWeights),
                Collections.unmodifiableMap(fundContributed),
                Collections.unmodifiableMap(effective),
                Collections.unmodifiableMap(sectorEffective),
                mfWeight, unmappedResidue, mfCoverage,
                directNameHHI, effectiveNameHHI, directSectorHHI, effectiveSectorHHI,
                feedsModel,
                Collections.unmodifiableList(notes),
                headline));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * Fractional NAV change for an AMFI scheme between its disclosure date and the
     * latest NAV on file, or empty when either NAV is missing. The investment's
     * {@code symbol} is the AMFI scheme code, which is also the mf_nav key.
     */
    private Optional<Double> navDriftSinceDisclosure(String schemeCode, LocalDate asOf) {
        Optional<MfNav> navAtDisclosure =
                mfNavRepo.findTopByAmfiCodeAndNavDateLessThanEqualOrderByNavDateDesc(schemeCode, asOf);
        Optional<MfNav> navLatest = mfNavRepo.findTopByAmfiCodeOrderByNavDateDesc(schemeCode);
        if (navAtDisclosure.isEmpty() || navLatest.isEmpty()) return Optional.empty();
        double base = navAtDisclosure.get().getNav().doubleValue();
        double now = navLatest.get().getNav().doubleValue();
        if (base <= 0) return Optional.empty();
        return Optional.of(now / base - 1.0);
    }

    private Optional<String> resolveSector(String symbol, String fallback) {
        SymbolExtractorService.SymbolEntry entry = symbolExtractorService.getSymbolEntry(symbol);
        if (entry != null && entry.sector != null && !entry.sector.isBlank()) {
            return Optional.of(entry.sector);
        }
        return (fallback != null && !fallback.isBlank()) ? Optional.of(fallback) : Optional.empty();
    }

    private static Map<String, Double> sectorWeights(Map<String, Double> nameWeights,
                                                      Map<String, String> sectorOf) {
        Map<String, Double> out = new LinkedHashMap<>();
        nameWeights.forEach((sym, w) ->
                out.merge(sectorOf.getOrDefault(sym, "UNKNOWN"), w, Double::sum));
        return out;
    }

    /** Herfindahl-Hirschman index Σ wᵢ² over the supplied weights. */
    static double hhi(Collection<Double> weights) {
        double sum = 0.0;
        for (double w : weights) sum += w * w;
        return sum;
    }

    private static String pct(double fraction) {
        return String.format("%.1f%%", fraction * 100);
    }
}
