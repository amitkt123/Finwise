package org.amit.finwise.cfo.service.analytics.options;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.analytics.options.BlackScholesService.OptionType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final double TRADING_DAYS_YEAR = 365.0; // calendar-day convention for EOD

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
}
