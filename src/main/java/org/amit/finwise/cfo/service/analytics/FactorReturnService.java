package org.amit.finwise.cfo.service.analytics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.config.FactorProperties;
import org.amit.finwise.cfo.service.StockPriceService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Builds daily factor-return series from persisted index price history.
 *
 *   MKT      = r(^NSEI)
 *   SIZE     = r(midcap index) − r(^NSEI)
 *   SECTOR_k = r(sector index k) − r(^NSEI)
 *
 * SIZE and sector factors are spreads over the market so the regressors stay
 * (approximately) decollinearized — raw index returns correlate ~0.9 with Nifty.
 * Sector factors are keyed by their index ticker (e.g. "^CNXIT"); the symbolic
 * names MKT/SIZE are reserved for the two style factors.
 *
 * Missing indices simply don't appear in the result — the factor model degrades
 * to the factors that have data rather than failing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactorReturnService {

    public static final String MKT_FACTOR = "MKT";
    public static final String SIZE_FACTOR = "SIZE";
    /** True Fama-French style factors (BPR-6), distinct from the index-spread SIZE. */
    public static final String SMB_FACTOR = "SMB";
    public static final String HML_FACTOR = "HML";

    /** Minimum cross-section width for a meaningful 2×3 Fama-French sort. */
    static final int FF_MIN_UNIVERSE = 10;
    /** Minimum rebalance periods before the SMB/HML series is usable. */
    static final int FF_MIN_PERIODS = 12;

    private final ReturnSeriesService returnSeriesService;
    private final FactorProperties factorProperties;

    /**
     * Builds all available factor series over [since, today].
     * Returns empty when the market series itself is missing — without MKT
     * there is no model.
     */
    public Optional<FactorSet> build(LocalDate since) {
        LinkedHashSet<String> indexSymbols = new LinkedHashSet<>(factorProperties.getIndices());
        indexSymbols.add(StockPriceService.NIFTY_SYMBOL);
        indexSymbols.add(factorProperties.getMidcapIndex());
        indexSymbols.addAll(factorProperties.getSectorIndex().values());

        Map<String, NavigableMap<LocalDate, Double>> indexReturns =
                returnSeriesService.getReturnSeries(new ArrayList<>(indexSymbols), since);

        NavigableMap<LocalDate, Double> mkt = indexReturns.get(StockPriceService.NIFTY_SYMBOL);
        if (mkt == null || mkt.isEmpty()) {
            log.warn("[FactorReturns] No {} history — factor model unavailable",
                    StockPriceService.NIFTY_SYMBOL);
            return Optional.empty();
        }

        NavigableMap<LocalDate, Double> size =
                spreadOverMarket(indexReturns.get(factorProperties.getMidcapIndex()), mkt);
        if (size == null) {
            log.warn("[FactorReturns] Midcap index {} missing — SIZE factor unavailable",
                    factorProperties.getMidcapIndex());
        }

        // Sector spreads keyed by index ticker; multiple gazetteer sectors may map
        // to the same index (Pharma + Healthcare → ^CNXPHARMA) — build each once.
        Map<String, NavigableMap<LocalDate, Double>> sectorSpreads = new LinkedHashMap<>();
        for (String idx : new TreeSet<>(factorProperties.getSectorIndex().values())) {
            NavigableMap<LocalDate, Double> spread = spreadOverMarket(indexReturns.get(idx), mkt);
            if (spread != null) sectorSpreads.put(idx, spread);
        }

        // True Fama-French SMB/HML (BPR-6) when the fundamentals cross-section is deep
        // enough; otherwise null and the model falls back to the index-spread SIZE above.
        FamaFrench ff = famaFrenchProvider != null
                ? famaFrenchProvider.build(since).orElse(null) : null;

        return Optional.of(new FactorSet(mkt, size, sectorSpreads,
                ff != null ? ff.smb() : null,
                ff != null ? ff.hml() : null,
                ff != null ? ff.note() : null));
    }

    /**
     * Optional supplier of true Fama-French factors from the fundamentals cross-section.
     * Left unset (null) until a fundamentals-backed assembler is wired — the factor model
     * then degrades to the labelled index-spread SIZE factor, as documented in BPR-6. A
     * supplier is injected via {@link #withFamaFrenchProvider} in tests and wiring.
     */
    private FamaFrenchProvider famaFrenchProvider;

    public interface FamaFrenchProvider {
        Optional<FamaFrench> build(LocalDate since);
    }

    public FactorReturnService withFamaFrenchProvider(FamaFrenchProvider provider) {
        this.famaFrenchProvider = provider;
        return this;
    }

    // ── True Fama-French SMB / HML (BPR-6) ─────────────────────────────────────

    /** One stock's cross-sectional inputs at a rebalance: size, value, and period return. */
    public record StockObs(String symbol, double marketCap, double bookToMarket, double periodReturn) {}

    /** One rebalance period: the formation date and the cross-section observed over it. */
    public record Rebalance(LocalDate date, List<StockObs> universe) {}

    /** SMB and HML returns for a single period, with the surviving universe size. */
    public record SmbHml(double smb, double hml, int n) {}

    /** Daily/periodic SMB & HML factor-return series with a lookback-depth disclosure note. */
    public record FamaFrench(NavigableMap<LocalDate, Double> smb,
                             NavigableMap<LocalDate, Double> hml, String note) {}

    /**
     * Fama-French (1993) 2×3 construction for one rebalance period.
     *
     * Independent sorts on size (median market cap → Small/Big) and value (B/M 30th/70th
     * percentiles → Low/Mid/High) form six value-weighted portfolios. Then
     *   SMB = ⅓(SL+SM+SH) − ⅓(BL+BM+BH),
     *   HML = ½(SH+BH)    − ½(SL+BL).
     * Returns empty when the universe is too thin or any of the six portfolios is empty
     * (the sort is then undefined). Stocks with non-positive book equity (B/M ≤ 0) or
     * market cap are dropped — they have no place in the value sort.
     */
    public static Optional<SmbHml> computeSmbHml(List<StockObs> universe) {
        if (universe == null) return Optional.empty();
        List<StockObs> valid = universe.stream()
                .filter(o -> o.marketCap() > 0 && o.bookToMarket() > 0
                        && !Double.isNaN(o.periodReturn()))
                .toList();
        if (valid.size() < FF_MIN_UNIVERSE) return Optional.empty();

        double sizeMedian = percentile(valid.stream().mapToDouble(StockObs::marketCap).toArray(), 0.50);
        double bmP30 = percentile(valid.stream().mapToDouble(StockObs::bookToMarket).toArray(), 0.30);
        double bmP70 = percentile(valid.stream().mapToDouble(StockObs::bookToMarket).toArray(), 0.70);

        List<StockObs> sl = new ArrayList<>(), sm = new ArrayList<>(), sh = new ArrayList<>();
        List<StockObs> bl = new ArrayList<>(), bm = new ArrayList<>(), bh = new ArrayList<>();
        for (StockObs o : valid) {
            boolean small = o.marketCap() <= sizeMedian;
            List<StockObs> bucket;
            if (o.bookToMarket() <= bmP30) bucket = small ? sl : bl;
            else if (o.bookToMarket() >= bmP70) bucket = small ? sh : bh;
            else bucket = small ? sm : bm;
            bucket.add(o);
        }
        if (sl.isEmpty() || sm.isEmpty() || sh.isEmpty()
                || bl.isEmpty() || bm.isEmpty() || bh.isEmpty()) {
            return Optional.empty();
        }

        double rSL = vwReturn(sl), rSM = vwReturn(sm), rSH = vwReturn(sh);
        double rBL = vwReturn(bl), rBM = vwReturn(bm), rBH = vwReturn(bh);

        double smb = (rSL + rSM + rSH) / 3.0 - (rBL + rBM + rBH) / 3.0;
        double hml = (rSH + rBH) / 2.0 - (rSL + rBL) / 2.0;
        return Optional.of(new SmbHml(smb, hml, valid.size()));
    }

    /**
     * Build the SMB/HML factor-return series across a sequence of rebalance periods
     * (typically one per trading day, with buckets fixed at the last monthly formation).
     * Empty when fewer than {@link #FF_MIN_PERIODS} periods yield a valid 2×3 sort — the
     * caller then falls back to the index-spread SIZE factor.
     */
    public Optional<FamaFrench> buildFamaFrench(List<Rebalance> rebalances) {
        if (rebalances == null || rebalances.isEmpty()) return Optional.empty();
        NavigableMap<LocalDate, Double> smb = new TreeMap<>();
        NavigableMap<LocalDate, Double> hml = new TreeMap<>();
        LocalDate first = null, last = null;
        for (Rebalance rb : rebalances) {
            Optional<SmbHml> s = computeSmbHml(rb.universe());
            if (s.isEmpty()) continue;
            smb.put(rb.date(), s.get().smb());
            hml.put(rb.date(), s.get().hml());
            if (first == null) first = rb.date();
            last = rb.date();
        }
        if (smb.size() < FF_MIN_PERIODS) {
            log.warn("[FamaFrench] Only {} valid periods (< {}) — SMB/HML unavailable, "
                    + "falling back to index-spread SIZE", smb.size(), FF_MIN_PERIODS);
            return Optional.empty();
        }
        String note = String.format(
                "FAMA_FRENCH_LOOKBACK: SMB/HML built from %d periods (%s → %s); "
                        + "factor depth limited by the fundamentals seed", smb.size(), first, last);
        return Optional.of(new FamaFrench(smb, hml, note));
    }

    /** Value-weighted (by market cap) average period return of a bucket. */
    private static double vwReturn(List<StockObs> bucket) {
        double wSum = 0, rSum = 0;
        for (StockObs o : bucket) {
            wSum += o.marketCap();
            rSum += o.marketCap() * o.periodReturn();
        }
        return wSum > 0 ? rSum / wSum : 0.0;
    }

    /** Linear-interpolated percentile (R-7) on an unsorted copy. */
    static double percentile(double[] values, double p) {
        double[] s = values.clone();
        Arrays.sort(s);
        int n = s.length;
        if (n == 0) return Double.NaN;
        if (n == 1) return s[0];
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        if (lo + 1 >= n) return s[n - 1];
        return s[lo] + (h - lo) * (s[lo + 1] - s[lo]);
    }

    /** Index-minus-market spread on the intersection of dates; null when the index is absent. */
    private static NavigableMap<LocalDate, Double> spreadOverMarket(
            NavigableMap<LocalDate, Double> index, NavigableMap<LocalDate, Double> mkt) {
        if (index == null || index.isEmpty()) return null;
        NavigableMap<LocalDate, Double> spread = new TreeMap<>();
        for (Map.Entry<LocalDate, Double> e : index.entrySet()) {
            Double m = mkt.get(e.getKey());
            if (m != null) spread.put(e.getKey(), e.getValue() - m);
        }
        return spread.isEmpty() ? null : spread;
    }

    /**
     * @param mkt          Nifty 50 daily returns (never null)
     * @param size         midcap-minus-Nifty spread; null when the midcap index has no data
     * @param sectorSpreads sector index ticker → index-minus-Nifty spread (only those with data)
     * @param smb          true Fama-French SMB returns; null when fundamentals are too thin (BPR-6)
     * @param hml          true Fama-French HML returns; null when fundamentals are too thin
     * @param famaFrenchNote lookback-depth disclosure for the SMB/HML series; null when absent
     */
    public record FactorSet(
            NavigableMap<LocalDate, Double> mkt,
            NavigableMap<LocalDate, Double> size,
            Map<String, NavigableMap<LocalDate, Double>> sectorSpreads,
            NavigableMap<LocalDate, Double> smb,
            NavigableMap<LocalDate, Double> hml,
            String famaFrenchNote
    ) {
        /** True when genuine Fama-French SMB/HML are available (vs the index-spread proxy). */
        public boolean hasFamaFrench() {
            return smb != null && hml != null && !smb.isEmpty() && !hml.isEmpty();
        }
    }
}
