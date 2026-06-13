package org.amit.finwise.marketdata.parser;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsers for the three NSE daily files, all emitting normalized records.
 * Only EQ and BE series rows are kept (cash-market equities).
 *
 * Old cm format (until early Jul-2024), positional with a trailing comma:
 *   SYMBOL,SERIES,OPEN,HIGH,LOW,CLOSE,LAST,PREVCLOSE,TOTTRDQTY,TOTTRDVAL,TIMESTAMP,TOTALTRADES,ISIN,
 *
 * UDiFF format (from Jul-2024), resolved by header name to survive column additions:
 *   TradDt,...,ISIN,TckrSymb,SctySrs,...,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,
 *   LastPric,PrvsClsgPric,...,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,...
 *
 * ind_close_all format:
 *   Index Name,Index Date(dd-MM-yyyy),Open,High,Low,Close,Points Change,Change(%),
 *   Volume,Turnover (Rs. Cr.),P/E,P/B,Div Yield        ("-" or blank = absent)
 */
@Slf4j
public final class BhavcopyParsers {

    private static final Set<String> KEPT_SERIES = Set.of("EQ", "BE");
    // case-insensitive: NSE renders months as "APR", Java's MMM expects "Apr"
    private static final DateTimeFormatter OLD_DATE = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter INDEX_DATE =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private BhavcopyParsers() {}

    public record EodRecord(
            String isin, String symbol, String series, String name, LocalDate tradeDate,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            BigDecimal last, BigDecimal prevClose, Long volume, BigDecimal turnover,
            Long trades) {}

    public record IndexRecord(
            String indexName, LocalDate tradeDate,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            BigDecimal pointsChange, BigDecimal changePct, Long volume,
            BigDecimal turnoverCr, BigDecimal pe, BigDecimal pb, BigDecimal divYield) {}

    public static List<EodRecord> parseOldCm(String csv) {
        List<EodRecord> records = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = splitCsv(lines[i]);
            if (f.length < 13 || !KEPT_SERIES.contains(f[1])) continue;
            try {
                records.add(new EodRecord(
                        f[12], f[0], f[1], null,
                        LocalDate.parse(f[10], OLD_DATE),
                        dec(f[2]), dec(f[3]), dec(f[4]), dec(f[5]),
                        dec(f[6]), dec(f[7]), lng(f[8]), dec(f[9]), lng(f[11])));
            } catch (RuntimeException e) {
                log.warn("[Bhavcopy] Skipping malformed old-format row: {} ({})", lines[i], e.getMessage());
            }
        }
        return records;
    }

    public static List<EodRecord> parseUdiff(String csv) {
        List<EodRecord> records = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) return records;

        Map<String, Integer> col = headerIndex(lines[0]);
        int tradDt = req(col, "TradDt"), isin = req(col, "ISIN"), sym = req(col, "TckrSymb"),
                srs = req(col, "SctySrs"), name = req(col, "FinInstrmNm"),
                open = req(col, "OpnPric"), high = req(col, "HghPric"), low = req(col, "LwPric"),
                close = req(col, "ClsPric"), last = req(col, "LastPric"),
                prev = req(col, "PrvsClsgPric"), vol = req(col, "TtlTradgVol"),
                trf = req(col, "TtlTrfVal"), txs = req(col, "TtlNbOfTxsExctd");

        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = splitCsv(lines[i]);
            if (f.length <= txs || !KEPT_SERIES.contains(f[srs])) continue;
            try {
                records.add(new EodRecord(
                        f[isin], f[sym], f[srs], f[name],
                        LocalDate.parse(f[tradDt]),
                        dec(f[open]), dec(f[high]), dec(f[low]), dec(f[close]),
                        dec(f[last]), dec(f[prev]), lng(f[vol]), dec(f[trf]), lng(f[txs])));
            } catch (RuntimeException e) {
                log.warn("[Bhavcopy] Skipping malformed UDiFF row: {} ({})", lines[i], e.getMessage());
            }
        }
        return records;
    }

    public record DeliveryRecord(
            String symbol, String series, LocalDate tradeDate,
            BigDecimal vwap, Long delivQty, BigDecimal delivPct) {}

    /**
     * sec_bhavdata_full: SYMBOL, SERIES, DATE1, PREV_CLOSE, OPEN_PRICE, HIGH_PRICE,
     * LOW_PRICE, LAST_PRICE, CLOSE_PRICE, AVG_PRICE, TTL_TRD_QNTY, TURNOVER_LACS,
     * NO_OF_TRADES, DELIV_QTY, DELIV_PER — fields are space-padded; only the
     * VWAP/delivery fields are extracted (prices come from the bhavcopy).
     */
    public static List<DeliveryRecord> parseSecBhavdataFull(String csv) {
        List<DeliveryRecord> records = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = splitCsv(lines[i]);
            if (f.length < 15) continue;
            String series = f[1].trim();
            if (!KEPT_SERIES.contains(series)) continue;
            try {
                records.add(new DeliveryRecord(
                        f[0].trim(), series,
                        LocalDate.parse(f[2].trim(), OLD_DATE),
                        dec(f[9]), lng(f[13]), dec(f[14])));
            } catch (RuntimeException e) {
                log.warn("[Bhavcopy] Skipping malformed sec_bhavdata row: {} ({})", lines[i], e.getMessage());
            }
        }
        return records;
    }

    public static List<IndexRecord> parseIndexCloseAll(String csv) {
        List<IndexRecord> records = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] f = splitCsv(lines[i]);
            if (f.length < 13) continue;
            try {
                records.add(new IndexRecord(
                        f[0].trim(), LocalDate.parse(f[1], INDEX_DATE),
                        dec(f[2]), dec(f[3]), dec(f[4]), dec(f[5]),
                        dec(f[6]), dec(f[7]), lng(f[8]),
                        dec(f[9]), dec(f[10]), dec(f[11]), dec(f[12])));
            } catch (RuntimeException e) {
                log.warn("[Bhavcopy] Skipping malformed index row: {} ({})", lines[i], e.getMessage());
            }
        }
        return records;
    }

    /** Minimal CSV splitter that honours double-quoted fields. */
    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static Map<String, Integer> headerIndex(String headerLine) {
        String[] headers = splitCsv(headerLine);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headers.length; i++) index.put(headers[i].trim(), i);
        return index;
    }

    private static int req(Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null) throw new IllegalArgumentException("UDiFF header missing column: " + name);
        return i;
    }

    private static BigDecimal dec(String v) {
        String t = v.trim();
        if (t.isEmpty() || t.equals("-")) return null;
        return new BigDecimal(t);
    }

    private static Long lng(String v) {
        String t = v.trim();
        if (t.isEmpty() || t.equals("-")) return null;
        // Some files render counts with decimals (e.g. "384.0")
        return new BigDecimal(t).longValue();
    }
}
