package org.amit.finwise.cfo.service.insight;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Brief-wide honesty validator — the prose counterpart to {@link InsightNarrationService}.
 *
 * <p>The Insight Cards section carries a structural guarantee: the LLM cannot be the source of a
 * number (figures are Java-authored and any unmatched numeric is stripped). The lead brief prose
 * — Portfolio Summary, News→Holdings impact, Risk narrative — had no such guarantee; it was
 * constrained only by a "do not make up numbers" system-prompt line. This service closes that gap.
 *
 * <p>Every <em>data-like</em> figure in the prose (a ₹ amount, a percentage, a decimal such as a
 * beta/ratio, or a thousands-grouped amount) must match — within rounding tolerance — a figure that
 * actually appears in the authoritative context the LLM was given. A sentence (or whole bullet /
 * action line) carrying a figure with no such match is treated as fabricated: it is stripped and
 * recorded in an auditable footer ({@link #redactionFooter}).
 *
 * <p>Deliberately conservative to avoid mangling a legitimate brief:
 * <ul>
 *   <li>Bare small integers, year tokens and horizon labels (list ordinals, "top 3–5", "0–7 days",
 *       "2024") are <b>not</b> data claims and are never scrutinised — only ₹/%/decimal/grouped
 *       figures are.</li>
 *   <li>A figure immediately following the word "confidence" is the model's own subjective score,
 *       not a datum, and is whitelisted.</li>
 *   <li>Markdown structure (headers, table rows, rules, blank lines) is preserved verbatim; the
 *       appended Java-computed card section is never passed through this filter.</li>
 * </ul>
 */
@Slf4j
@Service
public class BriefHonestyValidator {

    /** A numeric token: optional ₹, digits with thousands separators, optional decimals, optional %. */
    private static final Pattern NUMERIC = Pattern.compile("₹?\\s?-?\\d[\\d,]*(?:\\.\\d+)?%?");
    /** Relative tolerance for a prose figure to count as a faithful restatement of a context figure. */
    private static final double REL_TOL = 0.012;
    /** Max characters of a redacted fragment kept in the footer log. */
    private static final int REDACTION_PREVIEW = 160;

    /** Outcome of a sanitisation pass: the cleaned brief and the human-readable redaction log. */
    public record Result(String sanitizedBrief, List<String> redactions) {}

    /**
     * Strip every prose sentence/line carrying a data-like figure absent from {@code context}.
     * Pure and side-effect-free, so it is directly unit-testable. The returned brief has no footer
     * appended — the caller places {@link #redactionFooter} after the card section so the log sits
     * at the very end. Null/blank briefs pass through unchanged.
     */
    public Result sanitize(String brief, String context) {
        if (brief == null || brief.isBlank()) {
            return new Result(brief == null ? "" : brief, List.of());
        }
        Set<Double> allowed = allowedNumbers(context);
        List<String> redactions = new ArrayList<>();
        StringBuilder out = new StringBuilder(brief.length());
        String section = "Brief";

        for (String line : brief.split("\\R", -1)) {
            String trimmed = line.strip();

            if (isHeader(trimmed)) {
                section = headerText(trimmed);
                out.append(line).append('\n');
                continue;
            }
            if (isStructural(trimmed)) {                 // table row, rule, blank — keep verbatim
                out.append(line).append('\n');
                continue;
            }

            if (isListItem(trimmed)) {
                // A bullet / action line is one indivisible unit: keep it whole or drop it whole,
                // so a stripped figure never leaves a dangling "- SYMBOL:" with no claim behind it.
                if (numericsAllowed(line, allowed)) {
                    out.append(line).append('\n');
                } else {
                    redactions.add(redaction(section, trimmed));
                    log.debug("[BriefHonesty] dropped list line in '{}': {}", section, trimmed);
                }
                continue;
            }

            // Paragraph line: keep it sentence by sentence.
            List<String> kept = new ArrayList<>();
            for (String sentence : line.split("(?<=[.!?])\\s+")) {
                if (sentence.isBlank()) continue;
                if (numericsAllowed(sentence, allowed)) {
                    kept.add(sentence.strip());
                } else {
                    redactions.add(redaction(section, sentence.strip()));
                    log.debug("[BriefHonesty] stripped sentence in '{}': {}", section, sentence.strip());
                }
            }
            if (!kept.isEmpty()) {
                out.append(String.join(" ", kept)).append('\n');
            }
        }

        // Preserve a single trailing newline at most; trim the synthetic one we always append.
        String sanitized = out.length() > 0 && out.charAt(out.length() - 1) == '\n'
                ? out.substring(0, out.length() - 1) : out.toString();
        if (!redactions.isEmpty()) {
            log.info("[BriefHonesty] redacted {} unverifiable figure(s) from brief prose", redactions.size());
        }
        return new Result(sanitized, redactions);
    }

    /** True when every <em>scrutinised</em> numeric in the text matches an allowed context figure. */
    private boolean numericsAllowed(String text, Set<Double> allowed) {
        Matcher m = NUMERIC.matcher(text);
        while (m.find()) {
            String token = m.group();
            if (!isScrutinised(token)) continue;                 // bare int / year / horizon — not a claim
            if (isConfidenceContext(text, m.start())) continue;  // the model's own score
            Double v = parseNumeric(token);
            if (v == null) continue;
            if (allowed.stream().noneMatch(a -> faithful(a, v))) return false;
        }
        return true;
    }

    /**
     * Only figures that purport to be <em>data</em> are policed: ₹ amounts, percentages, decimals
     * (betas/ratios) and thousands-grouped amounts. Bare integers — list ordinals, small counts,
     * year tokens like "2024", horizon bounds like "0-7" — are structural, never fabrication risk,
     * and would generate noise, so they are left alone.
     */
    private static boolean isScrutinised(String token) {
        return token.indexOf('₹') >= 0 || token.indexOf('%') >= 0
                || token.indexOf('.') >= 0 || token.indexOf(',') >= 0;
    }

    /** True when the numeric at {@code idx} immediately follows the word "confidence". */
    private static boolean isConfidenceContext(String text, int idx) {
        int from = Math.max(0, idx - 15);
        return text.substring(from, idx).toLowerCase().contains("confidence");
    }

    /** A prose figure is faithful to a context figure within relative tolerance or at its own precision. */
    private static boolean faithful(double a, double v) {
        if (Math.abs(a - v) <= Math.max(1e-6, REL_TOL * Math.max(Math.abs(a), Math.abs(v)))) return true;
        // Rounding check: does the context value round to the prose value at the prose value's precision?
        int decimals = decimalsOf(v);
        double scale = Math.pow(10, decimals);
        return Math.round(a * scale) / scale == v;
    }

    private static int decimalsOf(double v) {
        String s = Double.toString(Math.abs(v));
        int dot = s.indexOf('.');
        if (dot < 0) return 0;
        String frac = s.substring(dot + 1);
        return "0".equals(frac) ? 0 : frac.length();
    }

    /** Every numeric value present anywhere in the authoritative context the LLM was given. */
    private Set<Double> allowedNumbers(String context) {
        Set<Double> nums = new LinkedHashSet<>();
        if (context == null) return nums;
        Matcher m = NUMERIC.matcher(context);
        while (m.find()) {
            Double v = parseNumeric(m.group());
            if (v != null) nums.add(v);
        }
        return nums;
    }

    private static Double parseNumeric(String token) {
        String cleaned = token.replace("₹", "").replace(",", "").replace("%", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isHeader(String trimmed) {
        return trimmed.startsWith("#");
    }

    private static String headerText(String trimmed) {
        return trimmed.replaceFirst("^#+\\s*", "").strip();
    }

    private static boolean isStructural(String trimmed) {
        return trimmed.isEmpty()
                || trimmed.startsWith("|")          // table row
                || trimmed.startsWith(">")          // blockquote (card narratives)
                || trimmed.chars().allMatch(c -> c == '-' || c == '=' || c == ' ' || c == '|'); // rule
    }

    private static boolean isListItem(String trimmed) {
        return trimmed.startsWith("-") || trimmed.startsWith("*") || trimmed.startsWith("•")
                || trimmed.startsWith("⏳") || trimmed.startsWith("📅") || trimmed.startsWith("🧱")
                || trimmed.matches("^\\d+[.)].*");  // ordered-list item
    }

    private static String redaction(String section, String fragment) {
        String f = fragment.length() > REDACTION_PREVIEW
                ? fragment.substring(0, REDACTION_PREVIEW) + "…" : fragment;
        return section + " — " + f;
    }

    /**
     * The auditable footer appended to the very end of a brief after the card section, listing what
     * the honesty filter removed. Empty string when nothing was redacted, so a clean brief is unchanged.
     */
    public static String redactionFooter(List<String> redactions) {
        if (redactions == null || redactions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n---\n");
        sb.append("_Honesty filter: redacted ").append(redactions.size())
          .append(redactions.size() == 1 ? " statement" : " statements")
          .append(" carrying a figure absent from the computed context._\n");
        for (String r : redactions) {
            sb.append("- ").append(r.replace("\n", " ").strip()).append('\n');
        }
        return sb.toString();
    }
}
