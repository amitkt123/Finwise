package org.amit.finwise.cfo.service.macro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeriesCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless parsers for the macro sources (DF-3). Each is tolerant of layout
 * noise but strict on values: a recognised row contributes only when its number
 * parses, and the calling provider applies the per-series sanity band. Endpoint
 * formats drift, so the contract each parser expects is documented at its method
 * and pinned by a fixture under {@code src/test/resources/macro/}.
 */
@Slf4j
public final class MacroSeriesParsers {

    private MacroSeriesParsers() {}

    // ── FBIL G-sec par yield curve ──────────────────────────────────────────

    /** Maturity token → years, e.g. "91 days"→0.25, "3 Months"→0.25, "10 Year"→10. */
    private static final Pattern TENOR =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(years?|y|months?|m|days?|d)\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final double TENOR_TOLERANCE = 0.08; // years

    /**
     * Parses the FBIL par-yield file (long form: a header row then one
     * {@code <maturity>,<par yield %>} row per tenor) into the four tenors the
     * curve signals need. Unrecognised maturities are ignored; only 3M / 1Y /
     * 5Y / 10Y (within tolerance) are returned.
     */
    public static Map<MacroSeriesCode, BigDecimal> parseFbilParYields(String csv) {
        Map<MacroSeriesCode, BigDecimal> out = new EnumMap<>(MacroSeriesCode.class);
        for (String line : csv.split("\\R")) {
            if (line.isBlank()) continue;
            String[] cells = line.split(",");
            if (cells.length < 2) continue;
            Double years = tenorYears(cells[0]);
            if (years == null) continue;
            MacroSeriesCode code = tenorToCode(years);
            if (code == null || out.containsKey(code)) continue;
            BigDecimal yield = lastDecimal(cells, 1);
            if (yield != null) out.put(code, yield);
        }
        return out;
    }

    private static Double tenorYears(String label) {
        Matcher m = TENOR.matcher(label);
        if (!m.find()) return null;
        double n = Double.parseDouble(m.group(1));
        char unit = Character.toLowerCase(m.group(2).charAt(0));
        return switch (unit) {
            case 'y' -> n;
            case 'm' -> n / 12.0;
            case 'd' -> n / 365.0;
            default -> null;
        };
    }

    private static MacroSeriesCode tenorToCode(double years) {
        if (Math.abs(years - 0.25) <= TENOR_TOLERANCE) return MacroSeriesCode.GSEC_3M;
        if (Math.abs(years - 1.0) <= TENOR_TOLERANCE) return MacroSeriesCode.GSEC_1Y;
        if (Math.abs(years - 5.0) <= TENOR_TOLERANCE) return MacroSeriesCode.GSEC_5Y;
        if (Math.abs(years - 10.0) <= TENOR_TOLERANCE) return MacroSeriesCode.GSEC_10Y;
        return null;
    }

    /** Last parseable decimal in cells[from..], or null. */
    private static BigDecimal lastDecimal(String[] cells, int from) {
        BigDecimal found = null;
        for (int i = from; i < cells.length; i++) {
            Matcher m = DECIMAL.matcher(cells[i].trim());
            if (m.matches()) found = new BigDecimal(cells[i].trim());
        }
        return found;
    }

    // ── FBIL USD/INR reference rate ─────────────────────────────────────────

    private static final Pattern USDINR =
            Pattern.compile("USD\\s*/?\\s*INR[^0-9-]*([0-9]{2,3}(?:\\.[0-9]+)?)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Extracts the USD/INR reference rate from FBIL's daily publication (CSV row
     * or page text). Returns empty when the "USD/INR" marker is absent.
     */
    public static Optional<BigDecimal> parseUsdInrReferenceRate(String text) {
        Matcher m = USDINR.matcher(text);
        return m.find() ? Optional.of(new BigDecimal(m.group(1))) : Optional.empty();
    }

    // ── RBI current policy rates ────────────────────────────────────────────

    private static Pattern rate(String label) {
        return Pattern.compile(label + "[^0-9%]*([0-9]+(?:\\.[0-9]+)?)\\s*%?",
                Pattern.CASE_INSENSITIVE);
    }

    private static final Pattern P_REPO = rate("Policy\\s+Repo\\s+Rate");
    private static final Pattern P_SDF  = rate("Standing\\s+Deposit\\s+Facility(?:\\s*\\(SDF\\))?\\s*Rate");
    private static final Pattern P_MSF  = rate("Marginal\\s+Standing\\s+Facility(?:\\s*\\(MSF\\))?\\s*Rate");
    private static final Pattern P_CRR  = rate("Cash\\s+Reserve\\s+Ratio");

    public record RbiRates(BigDecimal repo, BigDecimal sdf, BigDecimal msf, BigDecimal crr) {
        public boolean isEmpty() { return repo == null && sdf == null && msf == null && crr == null; }
    }

    /** Scrapes the RBI "current rates" panel for repo / SDF / MSF / CRR (any may be null). */
    public static RbiRates parseRbiRates(String html) {
        return new RbiRates(first(P_REPO, html), first(P_SDF, html),
                first(P_MSF, html), first(P_CRR, html));
    }

    private static BigDecimal first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? new BigDecimal(m.group(1)) : null;
    }

    // ── MOSPI prints via data.gov.in ────────────────────────────────────────

    public record MospiObs(LocalDate obsDate, BigDecimal value) {}

    /**
     * Parses a data.gov.in resource response ({@code {"records":[ ... ]}}) into
     * month-end observations. Field names vary per resource, so the month / year
     * / value keys are supplied by config. Rows missing any field, or whose value
     * is non-numeric, are skipped (logged at debug).
     */
    public static List<MospiObs> parseDataGovRecords(String json, String monthField,
                                                     String yearField, String valueField,
                                                     ObjectMapper mapper) {
        List<MospiObs> out = new ArrayList<>();
        try {
            JsonNode records = mapper.readTree(json).path("records");
            if (!records.isArray()) return out;
            for (JsonNode r : records) {
                Integer month = parseMonth(r.path(monthField).asText(null));
                Integer year = parseIntOrNull(r.path(yearField).asText(null));
                BigDecimal value = parseDecimalOrNull(r.path(valueField).asText(null));
                if (month == null || year == null || value == null) continue;
                out.add(new MospiObs(YearMonth.of(year, month).atEndOfMonth(), value));
            }
        } catch (Exception e) {
            log.warn("[MOSPI] Failed to parse data.gov.in response: {}", e.getMessage());
        }
        return out;
    }

    private static final String[] MONTHS = {
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"};

    private static Integer parseMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].startsWith(s) || s.startsWith(MONTHS[i])) return i + 1;
        }
        Integer n = parseIntOrNull(s);
        return (n != null && n >= 1 && n <= 12) ? n : null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException _) { return null; }
    }

    private static BigDecimal parseDecimalOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s.trim().replace(",", "")); } catch (NumberFormatException _) { return null; }
    }
}
