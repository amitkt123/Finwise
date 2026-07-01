package org.amit.finwise.cfo.service.analytics.options;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.IvTermStructureAlert;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.amit.finwise.marketdata.provider.DataEnvelope;
import org.amit.finwise.marketdata.provider.adapter.NSEOptionChainAdapter;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * EOD option-chain analytics (BPR-4): per-expiry implied-volatility smile and the
 * ATM IV term structure for an underlying.
 *
 * Operates on EOD option rows ({@link EodOptionRow}) — settlement price, strike,
 * expiry, type — which come from the free NSE F&O bhavcopy. Spot is the underlying's
 * EOD close; {@code r} from the existing FBIL/G-sec curve; {@code q} default 0. This
 * is the EOD analytic only: a live intraday vol surface needs a licensed tick feed
 * and is out of scope. Every output carries the as-of date so the EOD basis is
 * explicit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OptionChainService {

    private final ImpliedVolatilityService ivService;
    private final NSEOptionChainAdapter optionChainAdapter;

    private static final double TRADING_DAYS_YEAR = 365.0; // calendar-day convention for EOD

    /** NSE's option-chain-equities API renders expiry as e.g. "30-Jan-2026". */
    private static final DateTimeFormatter NSE_EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /** One EOD F&O contract row. */
    public record EodOptionRow(String underlying, OptionType type, double strike,
                               LocalDate expiry, double settlementPrice) {}

    /** A single (strike → IV) point on a smile. */
    public record SmilePoint(double strike, double moneyness, double impliedVol, String method) {}

    /** All IV points for one expiry, ascending by strike, plus the ATM IV. */
    public record ExpirySmile(LocalDate expiry, double tYears, double atmStrike,
                              double atmImpliedVol, List<SmilePoint> points) {}

    /** One point of the ATM term structure. */
    public record TermPoint(LocalDate expiry, double tYears, double atmImpliedVol) {}

    /**
     * Build the IV smile for each expiry of one underlying from its EOD rows.
     * Rows whose price violates no-arbitrage bounds are skipped (and logged), never
     * forced to a garbage σ. Empty expiries are dropped.
     */
    public List<ExpirySmile> buildSmiles(List<EodOptionRow> rows, double spot,
                                         double rate, double dividendYield, LocalDate asOf) {
        Map<LocalDate, List<EodOptionRow>> byExpiry = new TreeMap<>();
        for (EodOptionRow r : rows) {
            if (r.expiry() == null || !r.expiry().isAfter(asOf)) continue; // expired/expiring today
            byExpiry.computeIfAbsent(r.expiry(), k -> new ArrayList<>()).add(r);
        }

        List<ExpirySmile> smiles = new ArrayList<>();
        for (Map.Entry<LocalDate, List<EodOptionRow>> e : byExpiry.entrySet()) {
            LocalDate expiry = e.getKey();
            double tYears = ChronoUnit.DAYS.between(asOf, expiry) / TRADING_DAYS_YEAR;
            if (tYears <= 0) continue;

            List<SmilePoint> points = new ArrayList<>();
            for (EodOptionRow r : e.getValue()) {
                ivService.impliedVol(r.type(), r.settlementPrice(), spot, r.strike(),
                                tYears, rate, dividendYield)
                        .ifPresentOrElse(
                                iv -> points.add(new SmilePoint(
                                        r.strike(), r.strike() / spot, iv.vol(), iv.method())),
                                () -> log.debug("[Options] IV undefined (no-arb violation) for {} {} @ {}",
                                        r.underlying(), r.type(), r.strike()));
            }
            if (points.isEmpty()) continue;
            points.sort(Comparator.comparingDouble(SmilePoint::strike));

            // ATM = the smile point whose strike is closest to spot.
            SmilePoint atm = points.stream()
                    .min(Comparator.comparingDouble(p -> Math.abs(p.strike() - spot)))
                    .orElseThrow();
            smiles.add(new ExpirySmile(expiry, tYears, atm.strike(), atm.impliedVol(), points));
        }
        return smiles;
    }

    /**
     * ATM IV term structure: one ATM IV per expiry, ascending by maturity. Built from
     * {@link #buildSmiles}. The map preserves expiry order so callers can read the
     * short-to-long vol slope directly.
     */
    public List<TermPoint> atmTermStructure(List<EodOptionRow> rows, double spot,
                                            double rate, double dividendYield, LocalDate asOf) {
        List<TermPoint> term = new ArrayList<>();
        Map<LocalDate, Double> seen = new LinkedHashMap<>();
        for (ExpirySmile s : buildSmiles(rows, spot, rate, dividendYield, asOf)) {
            if (seen.putIfAbsent(s.expiry(), s.atmImpliedVol()) == null) {
                term.add(new TermPoint(s.expiry(), s.tYears(), s.atmImpliedVol()));
            }
        }
        term.sort(Comparator.comparingDouble(TermPoint::tYears));
        return term;
    }

    // ── Live option chain (BPR-4 gap-fill): IV term structure inversion ────────

    /** ATM IV at one live-chain expiry, used only to sort/compare across expiries. */
    private record LiveTermPoint(LocalDate expiry, double atmIv) {}

    /** Raw live option chain for a symbol from NSE's free endpoint (delegates to the adapter). */
    public DataEnvelope<Map<?, ?>> fetchLiveChain(String symbol) {
        return optionChainAdapter.fetchOptionChain(symbol);
    }

    /**
     * Detects IV term structure inversion (near-term ATM IV > far-term ATM IV) from the live
     * NSE option chain. Inversion signals near-term uncertainty priced richer than the medium
     * term — a signal worth flagging for derivatives holders.
     *
     * <p>Parses NSE's real {@code option-chain-equities} response shape:
     * {@code records.underlyingValue} (spot) and {@code records.data[]} (one row per
     * strike/expiry, each carrying {@code strikePrice}, {@code expiryDate}
     * ("dd-MMM-yyyy"), and {@code CE}/{@code PE} sides with {@code impliedVolatility}).
     * Degrades to {@link Optional#empty()} — never a fabricated value — whenever the chain is
     * missing, malformed, has fewer than two expiries with a usable ATM IV, or the term
     * structure is normal (near ≤ far).
     */
    public Optional<IvTermStructureAlert> detectTermStructureInversion(String symbol) {
        DataEnvelope<Map<?, ?>> envelope = fetchLiveChain(symbol);
        if (!envelope.isPresent()) return Optional.empty();

        try {
            List<LiveTermPoint> term = atmIvByExpiry(envelope.value());
            if (term.size() < 2) return Optional.empty();

            LiveTermPoint near = term.get(0);
            LiveTermPoint far = term.get(1);
            if (near.atmIv() <= far.atmIv()) return Optional.empty(); // normal term structure

            return Optional.of(new IvTermStructureAlert(
                    symbol, near.atmIv(), far.atmIv(), near.atmIv() - far.atmIv(),
                    String.format(Locale.ENGLISH,
                            "Near-term IV (%.1f%%, %s) > far-term IV (%.1f%%, %s). "
                                    + "Elevated short-term uncertainty detected.",
                            near.atmIv(), near.expiry(), far.atmIv(), far.expiry())));
        } catch (Exception e) {
            log.debug("[Options] term structure inversion check failed for {}: {}", symbol, e.getMessage());
            return Optional.empty();
        }
    }

    /** One ATM IV per expiry (nearest-to-spot strike), ascending by expiry date. */
    private List<LiveTermPoint> atmIvByExpiry(Map<?, ?> root) {
        Object recordsObj = root == null ? null : root.get("records");
        if (!(recordsObj instanceof Map<?, ?> records)) return List.of();

        Double underlying = toDouble(records.get("underlyingValue"));
        Object dataObj = records.get("data");
        if (underlying == null || !(dataObj instanceof List<?> rows)) return List.of();

        // Keep only the strike nearest spot per expiry — that row is the ATM quote.
        Map<String, Map<?, ?>> atmRowByExpiry = new LinkedHashMap<>();
        Map<String, Double> nearestDistanceByExpiry = new HashMap<>();
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            Object expiryObj = row.get("expiryDate");
            Double strike = toDouble(row.get("strikePrice"));
            if (!(expiryObj instanceof String expiry) || strike == null) continue;

            double distance = Math.abs(strike - underlying);
            Double best = nearestDistanceByExpiry.get(expiry);
            if (best == null || distance < best) {
                nearestDistanceByExpiry.put(expiry, distance);
                atmRowByExpiry.put(expiry, row);
            }
        }

        List<LiveTermPoint> term = new ArrayList<>();
        for (Map.Entry<String, Map<?, ?>> e : atmRowByExpiry.entrySet()) {
            LocalDate expiry;
            try {
                expiry = LocalDate.parse(e.getKey(), NSE_EXPIRY_FORMAT);
            } catch (Exception ex) {
                continue; // unparseable expiry label — skip rather than guess
            }
            Double atmIv = atmIv(e.getValue());
            if (atmIv == null || atmIv <= 0) continue; // 0 is NSE's "no trade" placeholder, not a real IV
            term.add(new LiveTermPoint(expiry, atmIv));
        }
        term.sort(Comparator.comparing(LiveTermPoint::expiry));
        return term;
    }

    /** Average of CE/PE implied vol when both are real; whichever side is present otherwise. */
    private Double atmIv(Map<?, ?> row) {
        Double ce = extractIv(row.get("CE"));
        Double pe = extractIv(row.get("PE"));
        if (ce != null && pe != null) return (ce + pe) / 2.0;
        return ce != null ? ce : pe;
    }

    private Double extractIv(Object sideObj) {
        if (!(sideObj instanceof Map<?, ?> side)) return null;
        return toDouble(side.get("impliedVolatility"));
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
