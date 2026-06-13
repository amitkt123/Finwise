package org.amit.finwise.marketdata.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.model.MarketDeal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parsers for the three DF-4 NSE JSON filings feeds — corporate announcements,
 * quarterly shareholding patterns, and bulk/block deals.
 *
 * NSE field names drift between site rebuilds, so every lookup is defensive:
 * each value is read from a list of candidate keys and missing fields degrade to
 * null rather than throwing. Re-verify field names if a feed starts producing
 * empty structured columns.
 */
@Slf4j
public final class FilingsParsers {

    private FilingsParsers() {}

    private static final DateTimeFormatter DATE = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter DATETIME = new java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH);

    // ── Announcements ────────────────────────────────────────────────────────

    public record AnnouncementRecord(
            String symbol, String companyName, String subject, String details,
            LocalDateTime broadcastAt, String attachmentUrl) {}

    public static List<AnnouncementRecord> parseAnnouncements(String json, ObjectMapper mapper) {
        List<AnnouncementRecord> out = new ArrayList<>();
        for (JsonNode n : array(json, mapper)) {
            String symbol = text(n, "symbol", "Symbol");
            if (symbol == null) continue;
            out.add(new AnnouncementRecord(
                    symbol,
                    text(n, "sm_name", "companyName", "comp", "name"),
                    text(n, "desc", "subject", "attchmntText"),
                    truncate(text(n, "attchmntText", "details", "smIndustry"), 2000),
                    dateTime(text(n, "an_dt", "exchdisstime", "sort_date", "dt")),
                    text(n, "attchmntFile", "attchmntText_url", "attachmentFile")));
        }
        return out;
    }

    // ── Shareholding patterns ──────────────────────────────────────────────────

    public record ShareholdingRecord(
            String symbol, String companyName, LocalDate periodEnded,
            BigDecimal promoterPct, BigDecimal promoterPledgePct,
            BigDecimal fiiPct, BigDecimal diiPct, BigDecimal publicPct) {}

    public static List<ShareholdingRecord> parseShareholding(String json, ObjectMapper mapper) {
        List<ShareholdingRecord> out = new ArrayList<>();
        for (JsonNode n : array(json, mapper)) {
            String symbol = text(n, "symbol", "Symbol");
            LocalDate period = date(text(n, "date", "asOnDate", "period", "submissionDate", "as_on"));
            if (symbol == null || period == null) continue;
            out.add(new ShareholdingRecord(
                    symbol,
                    text(n, "name", "companyName", "sm_name"),
                    period,
                    decimal(n, "pr_and_prgrp", "promoterAndPromoterGroup", "promoter_pct", "promoter"),
                    decimal(n, "shares_pledged", "pledge_pct", "pledgedPct", "promoterPledge"),
                    decimal(n, "fii_pct", "fpi", "foreign", "fiiPct"),
                    decimal(n, "dii_pct", "dii", "diiPct"),
                    decimal(n, "public_val", "public", "publicPct")));
        }
        return out;
    }

    // ── Bulk / block deals ─────────────────────────────────────────────────────

    public record DealRecord(
            LocalDate dealDate, String symbol, String securityName, String clientName,
            MarketDeal.Side side, Long quantity, BigDecimal price) {}

    public static List<DealRecord> parseDeals(String json, ObjectMapper mapper) {
        List<DealRecord> out = new ArrayList<>();
        for (JsonNode n : array(json, mapper)) {
            String symbol = text(n, "BD_SYMBOL", "symbol");
            LocalDate dealDate = date(text(n, "BD_DT_DATE", "mTIMESTAMP", "date", "BD_DATE"));
            if (symbol == null || dealDate == null) continue;
            out.add(new DealRecord(
                    dealDate,
                    symbol,
                    text(n, "BD_SCRIP_NAME", "securityName", "name"),
                    text(n, "BD_CLIENT_NAME", "clientName"),
                    side(text(n, "BD_BUY_SELL", "buySell", "side")),
                    longVal(n, "BD_QTY_TRD", "quantity", "qty"),
                    decimal(n, "BD_TP_WATP", "BD_DCA_PRICE", "price", "tradePrice", "watp")));
        }
        return out;
    }

    private static MarketDeal.Side side(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ENGLISH);
        if (u.startsWith("B")) return MarketDeal.Side.BUY;
        if (u.startsWith("S")) return MarketDeal.Side.SELL;
        return null;
    }

    // ── shared helpers ──────────────────────────────────────────────────────────

    private static Iterable<JsonNode> array(String json, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unparseable filings JSON: " + e.getMessage(), e);
        }
        JsonNode arr = root.isArray() ? root : root.path("data");
        if (!arr.isArray()) arr = mapper.createArrayNode();
        return arr;
    }

    private static String text(JsonNode n, String... fields) {
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull()) {
                String s = v.asText().trim();
                if (!s.isEmpty() && !s.equals("-")) return s;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode n, String... fields) {
        String s = text(n, fields);
        if (s == null) return null;
        try {
            return new BigDecimal(s.replace(",", "").replace("%", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longVal(JsonNode n, String... fields) {
        String s = text(n, fields);
        if (s == null) return null;
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String s) {
        if (s == null) return null;
        String head = s.length() > 11 ? s.substring(0, 11) : s; // tolerate trailing time
        try {
            return LocalDate.parse(head.trim(), DATE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDateTime dateTime(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s.trim(), DATETIME);
        } catch (RuntimeException e) {
            LocalDate d = date(s);
            return d == null ? null : d.atStartOfDay().with(LocalTime.MIDNIGHT);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
