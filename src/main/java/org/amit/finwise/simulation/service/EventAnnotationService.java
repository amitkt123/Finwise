package org.amit.finwise.simulation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.MacroSeriesCode;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.amit.finwise.cfo.service.macro.MacroSeriesService;
import org.amit.finwise.cfo.service.macro.RegimeModelService;
import org.amit.finwise.marketdata.model.IndexEod;
import org.amit.finwise.marketdata.repository.CorporateActionRepository;
import org.amit.finwise.marketdata.repository.IndexEodRepository;
import org.amit.finwise.simulation.dto.AnnotationType;
import org.amit.finwise.simulation.dto.EventAnnotation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventAnnotationService {

    private final MacroSeriesService macroSeriesService;
    private final CorporateActionRepository corporateActionRepository;
    private final RegimeModelService regimeModelService;
    private final ReturnSeriesService returnSeriesService;
    private final IndexEodRepository indexEodRepository;

    private static final double DRAWDOWN_THRESHOLD = -0.10;

    public List<EventAnnotation> annotate(String symbol, LocalDate from, LocalDate to) {
        List<EventAnnotation> all = new ArrayList<>();
        all.addAll(macroAnnotations(from, to));
        all.addAll(corporateAnnotations(symbol.toUpperCase(), from, to));
        all.addAll(regimeAnnotations(from));
        all.addAll(marketDrawdownAnnotations(from, to));
        all.sort(Comparator.comparing(EventAnnotation::date));
        return Collections.unmodifiableList(all);
    }

    // ── RBI REPO RATE changes ─────────────────────────────────────────────────

    private List<EventAnnotation> macroAnnotations(LocalDate from, LocalDate to) {
        List<EventAnnotation> out = new ArrayList<>();
        BigDecimal prev = null;
        LocalDate cursor = from.withDayOfMonth(1);
        while (!cursor.isAfter(to)) {
            Optional<BigDecimal> val = macroSeriesService.valueAsOf(MacroSeriesCode.REPO_RATE, cursor);
            if (val.isPresent()) {
                if (prev != null) {
                    double change = val.get().subtract(prev).doubleValue();
                    if (Math.abs(change) >= 0.10) {
                        String dir = change > 0 ? "+" : "";
                        out.add(new EventAnnotation(cursor,
                                String.format("RBI repo rate %s%.2f%%", dir, change),
                                AnnotationType.MACRO));
                    }
                }
                prev = val.get();
            }
            cursor = cursor.plusMonths(1);
        }
        return out;
    }

    // ── Corporate actions ────────────────────────────────────────────────────

    private List<EventAnnotation> corporateAnnotations(String symbol, LocalDate from, LocalDate to) {
        List<EventAnnotation> out = new ArrayList<>();
        try {
            corporateActionRepository.findAll().stream()
                    .filter(ca -> symbol.equalsIgnoreCase(ca.getSymbol()))
                    .filter(ca -> ca.getExDate() != null
                            && !ca.getExDate().isBefore(from)
                            && !ca.getExDate().isAfter(to))
                    .forEach(ca -> out.add(new EventAnnotation(
                            ca.getExDate(),
                            ca.getActionType() + ": " + ca.getSubject(),
                            AnnotationType.CORPORATE)));
        } catch (Exception e) {
            log.warn("Corporate action lookup failed for {}: {}", symbol, e.getMessage());
        }
        return out;
    }

    // ── Regime transitions ────────────────────────────────────────────────────

    private List<EventAnnotation> regimeAnnotations(LocalDate from) {
        List<EventAnnotation> out = new ArrayList<>();
        try {
            Map<String, NavigableMap<LocalDate, Double>> series =
                    returnSeriesService.getReturnSeries(List.of("^NSEI"), from);
            NavigableMap<LocalDate, Double> mkt = series.get("^NSEI");
            if (mkt == null || mkt.size() < ReturnSeriesService.MIN_OBSERVATIONS) return out;

            double[] rets = mkt.values().stream().mapToDouble(Double::doubleValue).toArray();
            regimeModelService.fit(rets).ifPresent(result ->
                    out.add(new EventAnnotation(
                            from.plusDays(mkt.size() / 2),
                            "Market regime: " + result.render(),
                            AnnotationType.REGIME)));
        } catch (Exception e) {
            log.warn("Regime annotation failed: {}", e.getMessage());
        }
        return out;
    }

    // ── Nifty drawdowns > 10% ─────────────────────────────────────────────────

    private List<EventAnnotation> marketDrawdownAnnotations(LocalDate from, LocalDate to) {
        List<EventAnnotation> out = new ArrayList<>();
        List<IndexEod> nifty = indexEodRepository
                .findByIndexNameIgnoreCaseAndTradeDateBetweenOrderByTradeDate("NIFTY 50", from, to);
        if (nifty.size() < 2) return out;

        double peak = nifty.get(0).getClose().doubleValue();
        for (IndexEod day : nifty) {
            double close = day.getClose().doubleValue();
            if (close > peak) { peak = close; continue; }
            double drawdown = (close - peak) / peak;
            if (drawdown <= DRAWDOWN_THRESHOLD) {
                out.add(new EventAnnotation(day.getTradeDate(),
                        String.format("Nifty drawdown %.1f%% from peak", drawdown * 100),
                        AnnotationType.MARKET));
                peak = close; // reset so we don't annotate the same trough repeatedly
            }
        }
        return out;
    }
}
