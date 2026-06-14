package org.amit.finwise.marketdata.parser;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for AMFI's NAV text format (NAVAll.txt and the date-range history
 * report share it). The body is sectioned:
 *
 * <pre>
 *   Scheme Code;ISIN Div Payout/ISIN Growth;ISIN Div Reinvestment;Scheme Name;Net Asset Value;Date
 *
 *   Open Ended Schemes(Equity Scheme - Large Cap Fund)     &lt;- category header
 *
 *   Aditya Birla Sun Life Mutual Fund                      &lt;- AMC header
 *
 *   119551;INF209K01VD7;INF209K01VE5;ABSL Frontline Equity;512.34;06-Jun-2024
 *   ...
 * </pre>
 *
 * Data rows have six {@code ;}-delimited fields; non-blank lines without
 * {@code ;} are headers — a category line if it looks like a scheme-structure
 * label, otherwise the AMC name. The leading column-title row is skipped.
 */
@Slf4j
public final class AmfiNavParser {

    private AmfiNavParser() {}

    /** dd-MMM-yyyy, the only date format AMFI emits. */
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    /**
     * One NAV observation with its scheme identity. {@code category} / {@code amc}
     * carry the section context so the scheme master can be upserted from the
     * same pass; {@code navDate} is the as-of date AMFI stamps on the row.
     */
    public record NavRecord(
            String amfiCode, String isinPayout, String isinReinvest, String schemeName,
            BigDecimal nav, LocalDate navDate, String category, String amc) {}

    public static List<NavRecord> parse(String body) {
        List<NavRecord> out = new ArrayList<>();
        if (body == null) return out;

        String currentCategory = null;
        String currentAmc = null;

        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.indexOf(';') < 0) {
                // Header line: either a scheme-structure category or an AMC name.
                if (looksLikeCategory(line)) {
                    currentCategory = line;
                } else {
                    currentAmc = line;
                }
                continue;
            }

            String[] f = line.split(";", -1);
            if (f.length < 6) continue;

            // Skip the column-title row.
            if (f[0].trim().equalsIgnoreCase("Scheme Code")) continue;

            String amfiCode = f[0].trim();
            if (amfiCode.isEmpty() || !amfiCode.chars().allMatch(Character::isDigit)) continue;

            BigDecimal nav = parseNav(f[4].trim());
            LocalDate navDate = parseDate(f[5].trim());
            if (nav == null || navDate == null) continue; // N.A. NAV / missing date

            out.add(new NavRecord(
                    amfiCode,
                    blankToNull(f[1]),
                    blankToNull(f[2]),
                    f[3].trim(),
                    nav,
                    navDate,
                    currentCategory,
                    currentAmc));
        }
        return out;
    }

    /**
     * Category headers describe the scheme structure ("Open Ended Schemes(...)",
     * "Close Ended ...", "Interval ..."); AMC names do not.
     */
    private static boolean looksLikeCategory(String line) {
        String l = line.toLowerCase(Locale.ENGLISH);
        return l.contains("schemes(") || l.contains("scheme(")
                || l.startsWith("open ended") || l.startsWith("close ended")
                || l.startsWith("interval");
    }

    private static BigDecimal parseNav(String s) {
        if (s.isEmpty() || s.equalsIgnoreCase("N.A.") || s.equalsIgnoreCase("NA")) return null;
        try {
            return new BigDecimal(s.replace(",", ""));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DATE_FMT);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return (t.isEmpty() || t.equalsIgnoreCase("-")) ? null : t;
    }
}
