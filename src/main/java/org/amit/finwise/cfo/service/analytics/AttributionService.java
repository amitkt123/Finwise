package org.amit.finwise.cfo.service.analytics;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.model.AttributionReport;
import org.amit.finwise.cfo.model.AttributionReport.SectorAttribution;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Brinson-Fachler attribution (Phase 12b).
 *
 * Per monthly bucket the portfolio's excess return over Nifty is split into allocation,
 * selection, and interaction effects per sector; buckets are then linked geometrically
 * (Cariño) so the linked effects reconcile to the compounded excess return with an
 * explicit residual.
 *
 * Sectors are keyed by NSE sector-index ticker so that a holding's gazetteer sector
 * (e.g. "Banking") and the benchmark's factsheet sector (e.g. "Financial Services")
 * — both of which map to {@code ^NSEBANK} via {@link FactorProperties} — land in the
 * same bucket. Benchmark sector RETURNS r_b,s come from the P8 sector-index series;
 * benchmark sector WEIGHTS come from {@code data/nifty_sector_weights.csv}.
 *
 * The arithmetic core ({@link #attributeSinglePeriod}) and the linking
 * ({@link #carinoLink}) are pure and golden-tested; {@link #compute} is the DB-backed
 * orchestration around them.
 */
@Slf4j
@Service
public class AttributionService {

    private final InvestmentRepository investmentRepository;
    private final ReturnSeriesService returnSeriesService;
    private final SymbolExtractorService symbolExtractorService;
    private final FactorProperties factorProperties;
    private final Resource benchmarkWeightsResource;

    /** Trailing calendar months of attribution buckets. */
    static final int WINDOW_MONTHS = 12;
    /** Benchmark weights older than this (days) force LOW_CONFIDENCE. */
    static final long STALE_DAYS = 120;

    public AttributionService(InvestmentRepository investmentRepository,
                              ReturnSeriesService returnSeriesService,
                              SymbolExtractorService symbolExtractorService,
                              FactorProperties factorProperties,
                              @Value("${cfo.attribution.benchmark-weights-path:classpath:data/nifty_sector_weights.csv}")
                              Resource benchmarkWeightsResource) {
        this.investmentRepository = investmentRepository;
        this.returnSeriesService = returnSeriesService;
        this.symbolExtractorService = symbolExtractorService;
        this.factorProperties = factorProperties;
        this.benchmarkWeightsResource = benchmarkWeightsResource;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Pure arithmetic core (golden-tested)
    // ══════════════════════════════════════════════════════════════════════════

    /** One sector's inputs for a single bucket. */
    public record SectorPeriod(double wP, double wB, double rP, double rB) {}

    /** Single-bucket attribution output; effects reconcile to rP − rB. */
    public record PeriodAttribution(
            Map<String, double[]> effects,   // sector → {allocation, selection, interaction}
            double rP, double rB) {
        public double excess() { return rP - rB; }
    }

    /**
     * Brinson-Fachler attribution for one period. Portfolio and benchmark weights are
     * each renormalized to sum to 1 over the supplied sectors (a prerequisite for the
     * Σ effects = r_p − r_b identity); with normalized inputs this is a no-op.
     */
    public static PeriodAttribution attributeSinglePeriod(Map<String, SectorPeriod> bySector) {
        double sumWp = bySector.values().stream().mapToDouble(SectorPeriod::wP).sum();
        double sumWb = bySector.values().stream().mapToDouble(SectorPeriod::wB).sum();
        if (sumWp <= 0 || sumWb <= 0) {
            return new PeriodAttribution(Map.of(), 0.0, 0.0);
        }

        double rP = 0.0, rB = 0.0;
        Map<String, double[]> norm = new LinkedHashMap<>();
        for (var e : bySector.entrySet()) {
            SectorPeriod s = e.getValue();
            double wp = s.wP() / sumWp;
            double wb = s.wB() / sumWb;
            norm.put(e.getKey(), new double[]{wp, wb, s.rP(), s.rB()});
            rP += wp * s.rP();
            rB += wb * s.rB();
        }

        Map<String, double[]> effects = new LinkedHashMap<>();
        for (var e : norm.entrySet()) {
            double[] v = e.getValue();
            double wp = v[0], wb = v[1], rp = v[2], rb = v[3];
            double allocation = (wp - wb) * (rb - rB);
            double selection = wb * (rp - rb);
            double interaction = (wp - wb) * (rp - rb);
            effects.put(e.getKey(), new double[]{allocation, selection, interaction});
        }
        return new PeriodAttribution(effects, rP, rB);
    }

    /**
     * Cariño geometric linking across buckets. Each bucket's effects are scaled by
     * {@code k_t / k} where {@code k_t} and {@code k} are the single-period and total
     * Cariño coefficients; the scaled effects then sum to the compounded excess return.
     *
     * @param periods    per-bucket attribution (chronological)
     * @return linked effects per sector plus compounded R_p, R_b and the residual
     */
    static LinkedResult carinoLink(List<PeriodAttribution> periods) {
        double cumP = 1.0, cumB = 1.0;
        for (PeriodAttribution p : periods) {
            cumP *= (1.0 + p.rP());
            cumB *= (1.0 + p.rB());
        }
        double Rp = cumP - 1.0;
        double Rb = cumB - 1.0;
        double k = carinoCoef(Rp, Rb);

        Map<String, double[]> linked = new LinkedHashMap<>();   // sector → {alloc, sel, inter}
        for (PeriodAttribution p : periods) {
            double kt = carinoCoef(p.rP(), p.rB());
            double scale = k != 0 ? kt / k : 0.0;
            for (var e : p.effects().entrySet()) {
                double[] agg = linked.computeIfAbsent(e.getKey(), x -> new double[3]);
                for (int i = 0; i < 3; i++) agg[i] += scale * e.getValue()[i];
            }
        }

        double alloc = 0, sel = 0, inter = 0;
        for (double[] v : linked.values()) { alloc += v[0]; sel += v[1]; inter += v[2]; }
        double residual = (alloc + sel + inter) - (Rp - Rb);
        return new LinkedResult(linked, Rp, Rb, alloc, sel, inter, residual);
    }

    /**
     * Cariño coefficient: (ln(1+Rp) − ln(1+Rb)) / (Rp − Rb), with the removable
     * singularity at Rp == Rb resolved to 1/(1+R).
     */
    private static double carinoCoef(double rp, double rb) {
        if (Math.abs(rp - rb) < 1e-12) return 1.0 / (1.0 + rp);
        return (Math.log1p(rp) - Math.log1p(rb)) / (rp - rb);
    }

    record LinkedResult(Map<String, double[]> linked, double Rp, double Rb,
                        double allocation, double selection, double interaction, double residual) {}

    // ══════════════════════════════════════════════════════════════════════════
    //  DB-backed orchestration
    // ══════════════════════════════════════════════════════════════════════════

    public Optional<AttributionReport> compute(String userId) {
        BenchmarkWeights bench = loadBenchmarkWeights();
        if (bench.byIndex.isEmpty()) {
            log.warn("[Attribution] no usable benchmark sector weights; skipping");
            return Optional.empty();
        }

        List<Investment> holdings = investmentRepository.findActiveInvestments(userId).stream()
                .filter(i -> i.getSymbol() != null && !i.getSymbol().isBlank())
                .filter(i -> i.getCurrentValue() != null
                        && i.getCurrentValue().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (holdings.isEmpty()) return Optional.empty();

        List<String> notes = new ArrayList<>(bench.notes);

        // Portfolio value by sector-index ticker (the canonical bucket key).
        Map<String, Double> pfValueByIndex = new LinkedHashMap<>();
        Map<String, List<HoldingRef>> holdingsByIndex = new LinkedHashMap<>();
        double unmappedPfValue = 0.0, totalPfValue = 0.0;
        for (Investment inv : holdings) {
            double val = inv.getCurrentValue().doubleValue();
            totalPfValue += val;
            String idx = factorProperties.indexForSector(resolveSector(inv)).orElse(null);
            if (idx == null) { unmappedPfValue += val; continue; }
            pfValueByIndex.merge(idx, val, Double::sum);
            holdingsByIndex.computeIfAbsent(idx, x -> new ArrayList<>())
                    .add(new HoldingRef(inv.getSymbol().toUpperCase(), val));
        }
        if (pfValueByIndex.isEmpty()) {
            notes.add("ATTR_NO_MAPPED_SECTORS: no holding mapped to a sector index");
            return Optional.empty();
        }
        if (unmappedPfValue > 0) {
            notes.add(String.format("ATTR_UNMAPPED_PF: %.1f%% of portfolio in sectors without a "
                    + "sector index — excluded from attribution",
                    unmappedPfValue / totalPfValue * 100));
        }

        // Return series for every holding plus every sector index we need.
        LocalDate since = LocalDate.now().minusMonths(WINDOW_MONTHS + 1).withDayOfMonth(1);
        Set<String> symbols = new LinkedHashSet<>();
        holdingsByIndex.values().forEach(l -> l.forEach(h -> symbols.add(h.symbol)));
        symbols.addAll(pfValueByIndex.keySet());
        var series = returnSeriesService.getReturnSeries(new ArrayList<>(symbols), since);

        // Monthly-compounded returns per symbol/index.
        Map<String, Map<YearMonth, Double>> monthly = new HashMap<>();
        series.forEach((sym, daily) -> monthly.put(sym, compoundByMonth(daily)));

        // Sector indices that actually have return history.
        Set<String> indices = new LinkedHashSet<>(pfValueByIndex.keySet());
        indices.removeIf(idx -> !monthly.containsKey(idx) || monthly.get(idx).isEmpty());
        if (indices.isEmpty()) {
            notes.add("ATTR_NO_INDEX_RETURNS: no sector index has return history in the window");
            return Optional.empty();
        }

        // Buckets: the trailing WINDOW_MONTHS complete months for which we have data.
        YearMonth end = YearMonth.now().minusMonths(1);             // last complete month
        List<YearMonth> months = new ArrayList<>();
        for (int i = WINDOW_MONTHS - 1; i >= 0; i--) months.add(end.minusMonths(i));

        List<PeriodAttribution> periods = new ArrayList<>();
        for (YearMonth ym : months) {
            Map<String, SectorPeriod> bucket = new LinkedHashMap<>();
            for (String idx : indices) {
                Double wb = bench.byIndex.get(idx);
                Double rbS = monthly.getOrDefault(idx, Map.of()).get(ym);
                if (wb == null || rbS == null) continue;            // sector absent this month
                double wp = pfValueByIndex.getOrDefault(idx, 0.0);  // raw value; normalized in core
                double rpS = portfolioSectorReturn(holdingsByIndex.get(idx), monthly, ym);
                if (Double.isNaN(rpS)) rpS = rbS;                   // no holding return → neutral
                bucket.put(idx, new SectorPeriod(wp, wb, rpS, rbS));
            }
            if (bucket.size() >= 2) periods.add(attributeSinglePeriod(bucket));
        }

        if (periods.size() < 2) {
            notes.add("ATTR_INSUFFICIENT_BUCKETS: fewer than 2 monthly buckets with ≥2 sectors");
            return Optional.empty();
        }

        LinkedResult linked = carinoLink(periods);

        List<SectorAttribution> sectors = linked.linked.entrySet().stream()
                .map(e -> new SectorAttribution(e.getKey(),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[0] + e.getValue()[1] + e.getValue()[2]))
                .sorted(Comparator.comparingDouble(SectorAttribution::total).reversed())
                .toList();

        long benchAge = bench.asOf != null ? ChronoUnit.DAYS.between(bench.asOf, LocalDate.now()) : 0;
        boolean lowConfidence = bench.asOf == null || benchAge > STALE_DAYS || unmappedPfValue > 0;
        if (bench.asOf != null && benchAge > STALE_DAYS) {
            notes.add(String.format("BENCH_STALE: Nifty sector weights are %dd old (asOf %s > %d) — "
                    + "refresh from the NSE factsheet", benchAge, bench.asOf, STALE_DAYS));
        }

        String headline = buildHeadline(linked, sectors);
        log.info("[Attribution] buckets={}, Rp={}%, Rb={}%, excess={}%, residual={}",
                periods.size(), fmtPct(linked.Rp), fmtPct(linked.Rb), fmtPct(linked.Rp - linked.Rb),
                String.format("%.2e", linked.residual));

        return Optional.of(new AttributionReport(
                bench.asOf,
                months.get(0).atDay(1), months.get(months.size() - 1).atEndOfMonth(),
                periods.size(),
                linked.Rp, linked.Rb, linked.Rp - linked.Rb,
                linked.allocation, linked.selection, linked.interaction, linked.residual,
                sectors,
                lowConfidence,
                Collections.unmodifiableList(notes),
                headline));
    }

    // ── orchestration helpers ──────────────────────────────────────────────────

    /** Value-weighted monthly return of the holdings mapped to one sector index. */
    private static double portfolioSectorReturn(List<HoldingRef> refs,
                                                Map<String, Map<YearMonth, Double>> monthly,
                                                YearMonth ym) {
        double wsum = 0.0, rsum = 0.0;
        for (HoldingRef h : refs) {
            Double r = monthly.getOrDefault(h.symbol, Map.of()).get(ym);
            if (r == null) continue;
            wsum += h.value;
            rsum += h.value * r;
        }
        return wsum > 0 ? rsum / wsum : Double.NaN;
    }

    /** Compound daily simple returns into a per-calendar-month return. */
    static Map<YearMonth, Double> compoundByMonth(NavigableMap<LocalDate, Double> daily) {
        Map<YearMonth, Double> growth = new LinkedHashMap<>();
        for (var e : daily.entrySet()) {
            growth.merge(YearMonth.from(e.getKey()), 1.0 + e.getValue(), (a, b) -> a * b);
        }
        Map<YearMonth, Double> out = new LinkedHashMap<>();
        growth.forEach((m, g) -> out.put(m, g - 1.0));
        return out;
    }

    private String resolveSector(Investment inv) {
        SymbolExtractorService.SymbolEntry entry =
                symbolExtractorService.getSymbolEntry(inv.getSymbol());
        if (entry != null && entry.sector != null && !entry.sector.isBlank()) return entry.sector;
        return inv.getSector();
    }

    private static String buildHeadline(LinkedResult l, List<SectorAttribution> sectors) {
        double excess = l.Rp - l.Rb;
        String verb = excess >= 0 ? "beat" : "lagged";
        String driver = Math.abs(l.allocation) >= Math.abs(l.selection)
                ? String.format("sector allocation (%+.2f%%)", l.allocation * 100)
                : String.format("stock selection (%+.2f%%)", l.selection * 100);
        String topSector = sectors.isEmpty() ? "" :
                String.format("; biggest contributor %s (%+.2f%%)",
                        sectors.get(0).sector(), sectors.get(0).total() * 100);
        return String.format("Portfolio %s Nifty by %+.2f%% over the window, driven mainly by %s%s",
                verb, excess * 100, driver, topSector);
    }

    private static String fmtPct(double d) { return String.format("%.2f", d * 100); }

    // ── benchmark weights CSV ───────────────────────────────────────────────────

    /** Benchmark weights aggregated to sector-index tickers, as fractions. */
    record BenchmarkWeights(Map<String, Double> byIndex, LocalDate asOf, List<String> notes) {}

    private BenchmarkWeights loadBenchmarkWeights() {
        Map<String, Double> byIndex = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        LocalDate asOf = null;
        double unmappedPct = 0.0;
        try {
            if (!benchmarkWeightsResource.exists()) {
                notes.add("BENCH_MISSING: nifty_sector_weights.csv not found");
                return new BenchmarkWeights(byIndex, null, notes);
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    benchmarkWeightsResource.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("#")) {
                        int i = t.toLowerCase().indexOf("asof=");
                        if (i >= 0) {
                            try { asOf = LocalDate.parse(t.substring(i + 5).trim()); }
                            catch (Exception ignored) { }
                        }
                        continue;
                    }
                    String[] c = t.split(",");
                    if (c.length < 2) continue;
                    String sector = c[0].trim();
                    double pct;
                    try { pct = Double.parseDouble(c[1].trim()); }
                    catch (NumberFormatException e) { continue; }
                    String idx = factorProperties.indexForSector(sector).orElse(null);
                    if (idx == null) { unmappedPct += pct; continue; }
                    byIndex.merge(idx, pct / 100.0, Double::sum);
                }
            }
        } catch (Exception e) {
            notes.add("BENCH_LOAD_FAILED: " + e.getMessage());
            return new BenchmarkWeights(byIndex, asOf, notes);
        }
        if (unmappedPct > 0) {
            notes.add(String.format("BENCH_UNMAPPED: %.1f%% of benchmark weight in sectors with no "
                    + "index (e.g. Others) — excluded from attribution", unmappedPct));
        }
        return new BenchmarkWeights(byIndex, asOf, notes);
    }

    private record HoldingRef(String symbol, double value) {}
}
