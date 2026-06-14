package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.EventOutcome;
import org.amit.finwise.cfo.model.InsightClaim;
import org.amit.finwise.cfo.repository.InsightClaimRepository;
import org.amit.finwise.marketdata.model.EodPrice;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.model.IngestionRun;
import org.amit.finwise.marketdata.model.Instrument;
import org.amit.finwise.marketdata.repository.EodPriceRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.marketdata.repository.IngestionRunRepository;
import org.amit.finwise.marketdata.repository.InstrumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DF-7 — the insight evaluation loop ("no prompt tuning without a scoreboard").
 *
 * <p>Two halves:
 * <ol>
 *   <li>{@link #extractClaims} — when a brief is generated, parse its actionable,
 *       directional claims (symbol, direction, horizon, confidence) and persist
 *       them as {@link InsightClaim} rows tagged with provider + prompt-version.</li>
 *   <li>{@link #scoreMaturedClaims} — nightly (after the bhavcopy lands), for every
 *       claim whose horizon has matured, compute the symbol's realized <em>excess</em>
 *       return vs Nifty from {@code md_eod_price} and mark whether the call was right.
 *       {@link #calibrationReport} then aggregates accuracy + Brier score per
 *       provider/prompt-version/horizon.</li>
 * </ol>
 *
 * The return math mirrors {@code EventOutcomeService}: back-adjusted close, excess
 * over the benchmark window, idempotent, and tolerant of not-yet-matured horizons.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightEvaluationService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Calendar span pulled to cover ~20 trading days plus weekends/holidays. */
    private static final int PRICE_LOOKAHEAD_DAYS = 45;
    /** Give up scoring a claim this many days after t0 (data will never arrive). */
    private static final int ABANDON_AFTER_DAYS = 40;
    /** The version stamp on every claim; bump when the brief prompt changes materially. */
    public static final String PROMPT_VERSION = "v1";

    // Matches "Confidence: 0.7", "**Confidence:** (0.7)", "*confidence: ~0.75~", etc.
    // [^0-9]{0,30} skips markdown decorators/colons/spaces between the keyword and the value.
    private static final Pattern CONFIDENCE =
            Pattern.compile("(?i)\\bconfidence\\b[^0-9]{0,30}([01](?:\\.\\d+)?)");

    private static final Set<String> BULLISH_WORDS = Set.of(
            "buy", "accumulate", "add", "bullish", "positive", "overweight",
            "increase", "long", "upside");
    private static final Set<String> BEARISH_WORDS = Set.of(
            "sell", "reduce", "trim", "exit", "bearish", "negative", "underweight",
            "decrease", "short", "downside", "book");

    private final InsightClaimRepository claimRepository;
    private final InstrumentRepository instrumentRepository;
    private final EodPriceRepository eodPriceRepository;
    private final IndexEodRepository indexEodRepository;
    private final IngestionRunRepository runRepository;

    @Value("${cfo.rag.benchmark-index:Nifty 50}")
    private String benchmarkIndex;

    // ── Extraction ────────────────────────────────────────────────────────────

    /**
     * Parse and persist the actionable directional claims in a freshly-generated
     * brief. Best-effort and side-effect-only: it never throws into brief
     * generation. Dedups within a brief on (symbol, horizon), keeping the highest
     * confidence; non-directional ("hold"/"watch") lines are skipped.
     */
    public int extractClaims(AiInsight insight, Set<String> portfolioSymbols) {
        if (insight == null || insight.getContent() == null || insight.getContent().isBlank()
                || portfolioSymbols == null || portfolioSymbols.isEmpty()) {
            return 0;
        }
        try {
            int written = 0;
            for (InsightClaim c : parseClaims(insight, portfolioSymbols)) {
                if (claimRepository.existsByInsightIdAndSymbolAndHorizon(
                        c.getInsightId(), c.getSymbol(), c.getHorizon())) continue;
                claimRepository.save(c);
                written++;
            }
            log.info("[Eval] Extracted {} actionable claims from insight {}", written, insight.getId());
            return written;
        } catch (RuntimeException e) {
            log.warn("[Eval] Claim extraction failed for insight {}: {}",
                    insight.getId(), e.getMessage());
            return 0;
        }
    }

    /**
     * Pure parse of a brief's markdown into deduped directional claims — no I/O,
     * so it is unit-testable. Tracks the current horizon bucket from section
     * headers ("Short-Term"/"Medium-Term"/…), and on each line carrying a
     * {@code Confidence:} score and an unambiguous direction, emits one claim per
     * mentioned holding. Dedup on (symbol, horizon) keeps the highest confidence.
     */
    List<InsightClaim> parseClaims(AiInsight insight, Set<String> portfolioSymbols) {
        Map<String, InsightClaim> best = new LinkedHashMap<>();  // key = symbol|horizon
        EventOutcome.Horizon horizon = EventOutcome.Horizon.H5D;  // default bucket

        // Collapse indented continuation lines (leading whitespace) into the preceding
        // bullet so multi-line action items are processed as a single unit.
        String[] rawLines = insight.getContent().split("\\R");
        List<String> lines = new ArrayList<>(rawLines.length);
        for (String raw : rawLines) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + " " + raw.trim());
            } else {
                lines.add(raw);
            }
        }

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            EventOutcome.Horizon h = detectHorizon(line);
            if (h != null) { horizon = h; continue; }

            Matcher m = CONFIDENCE.matcher(line);
            if (!m.find()) continue;
            double confidence = clamp01(Double.parseDouble(m.group(1)));

            InsightClaim.Direction dir = detectDirection(line);
            if (dir == null) continue;

            for (String symbol : matchedSymbols(line, portfolioSymbols)) {
                String key = symbol + "|" + horizon;
                InsightClaim existing = best.get(key);
                if (existing != null && existing.getConfidence() >= confidence) continue;
                best.put(key, InsightClaim.builder()
                        .insightId(insight.getId())
                        .userId(insight.getUserId())
                        .insightDate(insight.getInsightDate())
                        .symbol(symbol)
                        .direction(dir)
                        .horizon(horizon)
                        .confidence(confidence)
                        .provider(insight.getModelUsed())
                        .promptVersion(PROMPT_VERSION)
                        .build());
            }
        }
        return new ArrayList<>(best.values());
    }

    private EventOutcome.Horizon detectHorizon(String line) {
        String l = line.toLowerCase();
        if (l.contains("short-term") || l.contains("short term")
                || l.contains("0–7") || l.contains("0-7 day")) return EventOutcome.Horizon.H5D;
        if (l.contains("medium-term") || l.contains("medium term")
                || l.contains("1–3 month") || l.contains("1-3 month")) return EventOutcome.Horizon.H20D;
        if (l.contains("long-term") || l.contains("long term") || l.contains("1+ year"))
            return EventOutcome.Horizon.H20D;  // capped at the longest horizon we can measure
        return null;
    }

    private InsightClaim.Direction detectDirection(String line) {
        String l = line.toLowerCase();
        int bull = countMatches(l, BULLISH_WORDS);
        int bear = countMatches(l, BEARISH_WORDS);
        if (bull > bear) return InsightClaim.Direction.BULLISH;
        if (bear > bull) return InsightClaim.Direction.BEARISH;
        return null;  // ambiguous or "hold/watch" — not an actionable directional call
    }

    private int countMatches(String lower, Set<String> words) {
        int n = 0;
        for (String w : words) {
            int idx = lower.indexOf(w);
            while (idx >= 0) {
                boolean lb = idx == 0 || !Character.isLetter(lower.charAt(idx - 1));
                int end = idx + w.length();
                boolean rb = end >= lower.length() || !Character.isLetter(lower.charAt(end));
                if (lb && rb) n++;
                idx = lower.indexOf(w, idx + 1);
            }
        }
        return n;
    }

    /** Portfolio symbols that appear as standalone tokens in the line. */
    private List<String> matchedSymbols(String line, Set<String> symbols) {
        String upper = line.toUpperCase();
        List<String> hits = new ArrayList<>();
        for (String sym : symbols) {
            Pattern p = Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(sym) + "(?![A-Z0-9])");
            if (p.matcher(upper).find()) hits.add(sym);
        }
        return hits;
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    public record ScoreResult(int scanned, int scored, int abandoned) {}

    /**
     * Score every unscored claim whose horizon may have matured. Records an
     * ingestion-ledger row so the data-quality dashboard tracks the loop.
     */
    public ScoreResult scoreMaturedClaims() {
        LocalDateTime started = LocalDateTime.now();
        LocalDate today = LocalDate.now(IST);
        List<InsightClaim> pending = claimRepository.findUnscoredBefore(today.minusDays(1));

        int scored = 0, abandoned = 0;
        for (InsightClaim claim : pending) {
            try {
                Integer outcome = score(claim);          // null = not matured yet
                if (outcome != null) { scored++; continue; }
                if (claim.getInsightDate().isBefore(today.minusDays(ABANDON_AFTER_DAYS))) {
                    claim.setScored(true);
                    claim.setScoredAt(LocalDateTime.now());  // correct stays null = unscoreable
                    claimRepository.save(claim);
                    abandoned++;
                }
            } catch (RuntimeException e) {
                log.warn("[Eval] Scoring failed for claim {}: {}", claim.getId(), e.getMessage());
            }
        }

        IngestionRun.Status status = pending.isEmpty()
                ? IngestionRun.Status.NO_DATA : IngestionRun.Status.SUCCESS;
        ledger(today, status, scored,
                "pending=" + pending.size() + " scored=" + scored + " abandoned=" + abandoned, started);
        log.info("[Eval] Claim scoring: {} pending, {} scored, {} abandoned",
                pending.size(), scored, abandoned);
        return new ScoreResult(pending.size(), scored, abandoned);
    }

    /** Returns 1/0 once scored, or null if the horizon has not matured yet. */
    private Integer score(InsightClaim claim) {
        Optional<Instrument> instrument = instrumentRepository.findBySymbol(claim.getSymbol());
        if (instrument.isEmpty()) return null;

        List<EodPrice> series = eodPriceRepository
                .findByInstrumentIdAndTradeDateBetweenOrderByTradeDate(
                        instrument.get().getId(), claim.getInsightDate(),
                        claim.getInsightDate().plusDays(PRICE_LOOKAHEAD_DAYS));
        int days = claim.getHorizon().tradingDays;
        if (series.size() <= days) return null;          // not matured

        EodPrice base = series.getFirst();
        double basePrice = priceOf(base);
        if (basePrice <= 0) return null;
        EodPrice horizonRow = series.get(days);

        double rawReturn = (priceOf(horizonRow) - basePrice) / basePrice;
        Double benchReturn = benchmarkReturn(base.getTradeDate(), horizonRow.getTradeDate());
        Double excess = benchReturn == null ? null : rawReturn - benchReturn;

        boolean correct;
        if (excess != null) {
            correct = claim.getDirection() == InsightClaim.Direction.BULLISH ? excess > 0 : excess < 0;
        } else {
            // No benchmark window — fall back to raw direction so the call is still judged.
            correct = claim.getDirection() == InsightClaim.Direction.BULLISH ? rawReturn > 0 : rawReturn < 0;
        }

        claim.setRawReturn(rawReturn);
        claim.setExcessReturnVsNifty(excess);
        claim.setCorrect(correct);
        claim.setScored(true);
        claim.setScoredAt(LocalDateTime.now());
        claimRepository.save(claim);
        return correct ? 1 : 0;
    }

    private Double benchmarkReturn(LocalDate from, LocalDate to) {
        List<IndexEod> rows = indexEodRepository
                .findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate(benchmarkIndex, from, to);
        if (rows.size() < 2) return null;
        BigDecimal first = rows.getFirst().getClose();
        BigDecimal last = rows.getLast().getClose();
        if (first == null || last == null || first.signum() == 0) return null;
        return last.subtract(first).doubleValue() / first.doubleValue();
    }

    private double priceOf(EodPrice p) {
        BigDecimal v = p.getAdjClose() != null ? p.getAdjClose() : p.getClose();
        return v != null ? v.doubleValue() : 0.0;
    }

    // ── Calibration report ────────────────────────────────────────────────────

    /**
     * One row of the scoreboard: a (provider, prompt-version, horizon) cohort with
     * its hit rate, average self-reported confidence, and Brier score. A
     * well-calibrated provider has {@code hitRate ≈ avgConfidence} and a low Brier.
     */
    public record CalibrationRow(
            String provider, String promptVersion, EventOutcome.Horizon horizon,
            int n, int hits, double hitRate, double avgConfidence, double brierScore) {

        public String render() {
            return String.format(
                    "%-10s %-4s %-4s | n=%-3d hit=%4.1f%% conf=%4.1f%% brier=%.3f",
                    provider, promptVersion, horizon, n,
                    hitRate * 100, avgConfidence * 100, brierScore);
        }
    }

    /** Aggregate scored claims from the last {@code sinceDays} into the scoreboard. */
    public List<CalibrationRow> calibrationReport(int sinceDays) {
        List<InsightClaim> claims = claimRepository.findScoredSince(LocalDate.now(IST).minusDays(sinceDays));

        // key = provider|promptVersion|horizon
        Map<String, int[]> counts = new LinkedHashMap<>();      // [n, hits]
        Map<String, double[]> sums = new LinkedHashMap<>();     // [confSum, brierSum]
        Map<String, InsightClaim> sample = new LinkedHashMap<>();

        for (InsightClaim c : claims) {
            if (c.getCorrect() == null) continue;
            String key = c.getProvider() + "|" + c.getPromptVersion() + "|" + c.getHorizon();
            int outcome = c.getCorrect() ? 1 : 0;
            double conf = c.getConfidence() != null ? c.getConfidence() : 0.0;

            counts.computeIfAbsent(key, _ -> new int[2]);
            sums.computeIfAbsent(key, _ -> new double[2]);
            sample.putIfAbsent(key, c);
            counts.get(key)[0]++;
            counts.get(key)[1] += outcome;
            sums.get(key)[0] += conf;
            sums.get(key)[1] += (conf - outcome) * (conf - outcome);
        }

        List<CalibrationRow> rows = new ArrayList<>();
        for (String key : counts.keySet()) {
            int n = counts.get(key)[0];
            int hits = counts.get(key)[1];
            InsightClaim s = sample.get(key);
            rows.add(new CalibrationRow(
                    s.getProvider(), s.getPromptVersion(), s.getHorizon(),
                    n, hits, n == 0 ? 0 : (double) hits / n,
                    n == 0 ? 0 : sums.get(key)[0] / n,
                    n == 0 ? 0 : sums.get(key)[1] / n));
        }
        return rows;
    }

    private void ledger(LocalDate businessDate, IngestionRun.Status status, int rows,
                        String message, LocalDateTime started) {
        IngestionRun run = runRepository
                .findByJobNameAndBusinessDate(IngestionRun.JOB_INSIGHT_EVAL, businessDate)
                .orElseGet(() -> IngestionRun.builder()
                        .jobName(IngestionRun.JOB_INSIGHT_EVAL).businessDate(businessDate).build());
        run.setStatus(status);
        run.setRowCount(rows);
        run.setMessage(message);
        run.setStartedAt(started);
        run.setFinishedAt(LocalDateTime.now());
        runRepository.save(run);
    }
}
