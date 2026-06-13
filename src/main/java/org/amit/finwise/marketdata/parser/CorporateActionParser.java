package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.CorporateActionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the NSE corporates-corporateActions JSON array and classifies each
 * free-text subject line into a structured action.
 *
 * Subject grammars seen in the live feed (2020→today):
 *   Face Value Split (Sub-Division) - From Rs10/- Per Share To Re 1/- Per Share
 *   Bonus 3:1
 *   Rights 6:179 @ Premium Rs 1810/-
 *   Interim Dividend - Rs 9 Per Share Special Dividend - Rs 18 Per Share
 */
@Slf4j
public final class CorporateActionParser {

    private static final DateTimeFormatter NSE_DATE = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private static final Pattern SPLIT_FV = Pattern.compile(
            "From\\s+(?:Rs|Re)\\.?\\s*([\\d.]+).*?To\\s+(?:Rs|Re)\\.?\\s*([\\d.]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RATIO = Pattern.compile("(\\d+)\\s*:\\s*(\\d+)");
    private static final Pattern DIV_AMOUNT = Pattern.compile(
            "(?:Rs|Re)\\.?\\s*([\\d.]+)\\s*Per\\s+Share", Pattern.CASE_INSENSITIVE);

    private CorporateActionParser() {}

    public record CaRecord(
            String isin, String symbol, String series, String companyName,
            CorporateActionType actionType, String subject,
            LocalDate exDate, LocalDate recordDate,
            Integer ratioNew, Integer ratioOld,
            BigDecimal fromFaceValue, BigDecimal toFaceValue, BigDecimal dividendAmount) {}

    public static List<CaRecord> parse(String json, ObjectMapper mapper) {
        List<CaRecord> out = new ArrayList<>();
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable CA JSON: " + e.getMessage(), e);
        }
        JsonNode arr = root.isArray() ? root : root.path("data");
        if (!arr.isArray()) return out;

        for (JsonNode n : arr) {
            String isin = text(n, "isin");
            String symbol = text(n, "symbol");
            String subject = text(n, "subject");
            if (isin == null || symbol == null || subject == null) continue;
            try {
                CorporateActionType type = classify(subject);
                CaRecord r = new CaRecord(
                        isin, symbol, text(n, "series"), text(n, "comp"),
                        type, subject.trim(),
                        date(text(n, "exDate")), date(text(n, "recDate")),
                        null, null, null, null, null);
                out.add(enrich(r, type, subject));
            } catch (RuntimeException e) {
                log.warn("[CorpAction] Skipping malformed row {}: {}", subject, e.getMessage());
            }
        }
        return out;
    }

    static CorporateActionType classify(String subject) {
        String s = subject.toLowerCase(Locale.ENGLISH);
        if (s.contains("split") || s.contains("sub-division") || s.contains("subdivision"))
            return CorporateActionType.SPLIT;
        if (s.contains("bonus")) return CorporateActionType.BONUS;
        if (s.contains("right")) return CorporateActionType.RIGHTS;
        if (s.contains("dividend")) return CorporateActionType.DIVIDEND;
        return CorporateActionType.OTHER;
    }

    private static CaRecord enrich(CaRecord r, CorporateActionType type, String subject) {
        switch (type) {
            case SPLIT -> {
                Matcher m = SPLIT_FV.matcher(subject);
                if (m.find()) {
                    return with(r, null, null, dec(m.group(1)), dec(m.group(2)), null);
                }
            }
            case BONUS, RIGHTS -> {
                Matcher m = RATIO.matcher(subject);
                if (m.find()) {
                    return with(r, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                            null, null, null);
                }
            }
            case DIVIDEND -> {
                Matcher m = DIV_AMOUNT.matcher(subject);
                BigDecimal total = BigDecimal.ZERO;
                boolean any = false;
                while (m.find()) {
                    total = total.add(dec(m.group(1)));
                    any = true;
                }
                if (any) return with(r, null, null, null, null, total);
            }
            default -> { /* OTHER: nothing structured */ }
        }
        return r;
    }

    private static CaRecord with(CaRecord r, Integer rn, Integer ro,
                                 BigDecimal fromFv, BigDecimal toFv, BigDecimal div) {
        return new CaRecord(r.isin(), r.symbol(), r.series(), r.companyName(), r.actionType(),
                r.subject(), r.exDate(), r.recordDate(), rn, ro, fromFv, toFv, div);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText().trim();
        return (s.isEmpty() || s.equals("-")) ? null : s;
    }

    private static LocalDate date(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s, NSE_DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal dec(String s) {
        try {
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
