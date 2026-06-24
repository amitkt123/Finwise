package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.StockPriceService;
import org.amit.finwise.cfo.service.analytics.ReturnSeriesService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Small market-model event-study engine (Roadmap Phase 1, item 4).
 *
 * Around each past event date it measures the stock's <em>abnormal</em> return —
 * the part of the move not explained by the market. The market-adjusted model is
 * used: expected return on a day equals the Nifty return that day, so
 *   AR_t = r_stock_t − r_nifty_t
 *   CAR  = Σ AR_t over the post-event window
 * Averaging across events gives the Average Abnormal Return path (AAR) and the
 * mean Cumulative Abnormal Return — e.g. "TCS historically drifts +1.8% in the
 * 3 trading days after results."
 *
 * All returns come from {@link ReturnSeriesService} (the single canonical return
 * source); no return maths is duplicated here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventStudyService {

    private final ReturnSeriesService returnSeriesService;

    /** Minimum past events required for the averaged result to be meaningful. */
    private static final int MIN_EVENTS = 3;

    /**
     * Run a post-event drift study for {@code symbol} over the supplied past event
     * dates, looking {@code windowDays} trading days forward from each event.
     *
     * @return empty when there are too few events or insufficient return history.
     */
    public java.util.Optional<EventStudyResult> studyPostEvent(String symbol,
                                                               List<LocalDate> eventDates,
                                                               int windowDays) {
        if (symbol == null || eventDates == null || eventDates.isEmpty() || windowDays < 1) {
            return java.util.Optional.empty();
        }
        String sym = symbol.toUpperCase().trim();

        LocalDate earliest = eventDates.stream().min(LocalDate::compareTo).orElse(null);
        if (earliest == null) return java.util.Optional.empty();
        // Pad the lookback so the earliest event still has a full forward window.
        LocalDate since = earliest.minusDays(10);

        Map<String, NavigableMap<LocalDate, Double>> series = returnSeriesService.getReturnSeries(
                List.of(sym, StockPriceService.NIFTY_SYMBOL), since);
        NavigableMap<LocalDate, Double> stockReturns = series.get(sym);
        NavigableMap<LocalDate, Double> niftyReturns = series.get(StockPriceService.NIFTY_SYMBOL);
        if (stockReturns == null || stockReturns.isEmpty()) {
            log.debug("[EventStudy] {} — insufficient return history", sym);
            return java.util.Optional.empty();
        }

        double[] aarSum = new double[windowDays];
        int[] aarCount = new int[windowDays];
        double carSum = 0.0;
        int usableEvents = 0;

        for (LocalDate event : eventDates) {
            // Post-event trading days are the first windowDays return dates on/after the event.
            List<LocalDate> postDays = new ArrayList<>(stockReturns.tailMap(event, true).keySet());
            if (postDays.isEmpty()) continue;
            int n = Math.min(windowDays, postDays.size());
            double eventCar = 0.0;
            for (int k = 0; k < n; k++) {
                LocalDate day = postDays.get(k);
                double rStock = stockReturns.getOrDefault(day, 0.0);
                double rMkt = niftyReturns != null ? niftyReturns.getOrDefault(day, 0.0) : 0.0;
                double ar = rStock - rMkt;
                aarSum[k] += ar;
                aarCount[k]++;
                eventCar += ar;
            }
            carSum += eventCar;
            usableEvents++;
        }

        if (usableEvents < MIN_EVENTS) {
            log.debug("[EventStudy] {} — only {} usable events (<{}), skipping", sym, usableEvents, MIN_EVENTS);
            return java.util.Optional.empty();
        }

        List<Double> aar = new ArrayList<>(windowDays);
        for (int k = 0; k < windowDays; k++) {
            aar.add(aarCount[k] > 0 ? aarSum[k] / aarCount[k] : 0.0);
        }
        double meanCar = carSum / usableEvents;

        String summary = String.format(
                "Over %d past events, %s drifts %+.2f%% (market-adjusted) in the %d trading days after the event.",
                usableEvents, sym, meanCar * 100, windowDays);

        return java.util.Optional.of(new EventStudyResult(
                sym, usableEvents, windowDays, meanCar, meanCar * 100, aar, summary));
    }

    /**
     * Average abnormal-return path and cumulative drift around a class of events.
     *
     * @param meanCar        mean cumulative abnormal return over the window (decimal, e.g. 0.018)
     * @param meanCarPercent {@code meanCar} as a percentage
     * @param aar            average abnormal return per post-event trading day (decimal)
     */
    public record EventStudyResult(
            String symbol,
            int eventCount,
            int windowDays,
            double meanCar,
            double meanCarPercent,
            List<Double> aar,
            String summary
    ) {}
}
