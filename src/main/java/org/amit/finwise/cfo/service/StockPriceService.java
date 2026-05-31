package org.amit.finwise.cfo.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.DataQualityFlag;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.amit.finwise.cfo.service.price.PriceDataProvider;
import org.amit.finwise.cfo.service.price.PriceDataProvider.DailyPrice;
import org.amit.finwise.cfo.service.price.PriceDataProvider.PriceProviderException;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Fetches and persists daily OHLCV price history for all active portfolio stocks.
 *
 * Uses a configurable fallback chain of PriceDataProviders (Yahoo Finance → Alpha Vantage → NSE India).
 * If the primary provider fails (rate limit, timeout, bad response), the next provider is tried
 * automatically. The fallback chain is built by PriceProviderConfig.
 *
 * Features:
 *  - Multi-provider fallback with per-symbol retry
 *  - UPSERT semantics: skips dates already stored
 *  - Day-over-day priceChangePercent computation
 *  - Circuit breaker detection (high==low heuristic + large % moves)
 *  - consecutiveCircuitCount tracking
 *  - Exponential backoff between symbols to respect rate limits
 *
 * Called daily by CFOScheduler at 4 PM IST (after NSE market close at 3:30 PM).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockPriceService {

    private final StockPriceHistoryRepository priceRepo;
    private final InvestmentRepository investmentRepo;
    private final PortfolioSnapshotRepository snapshotRepo;
    private final List<PriceDataProvider> providers; // injected in priority order by PriceProviderConfig

    @Value("${cfo.price.history-days:30}")
    private int historyDays;

    @Value("${cfo.price.inter-symbol-delay-ms:500}")
    private long interSymbolDelayMs;

    // NSE circuit limit thresholds (percentage)
    // NSE applies ±5%, ±10%, or ±20% limits based on stock tier
    // We use ±9.5% as a conservative proxy (catches ±10% and above)
    private static final double CIRCUIT_THRESHOLD_PERCENT = 9.5;

    // A raw-close drop beyond this threshold is treated as a corporate-action artifact
    // (e.g. 1:5 split ≈ -80%) and flagged SUSPECT_GAP rather than a real lower circuit
    private static final double SPLIT_SUSPECT_THRESHOLD_PERCENT = -70.0;

    /** Yahoo Finance ticker for Nifty 50 index (no .NS suffix). */
    public static final String NIFTY_SYMBOL = "^NSEI";

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Main entry point — fetches 30 days of price history for all active portfolio symbols.
     * Called by CFOScheduler daily at 4 PM IST.
     */
    public void fetchAndPersistPrices(String userId) {
        List<Investment> activeInvestments = investmentRepo.findActiveInvestments(userId);
        if (activeInvestments.isEmpty()) {
            log.debug("[PriceService] No active investments for user={}", userId);
            return;
        }

        List<String> symbols = activeInvestments.stream()
                .map(Investment::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .toList();

        log.info("[PriceService] Fetching prices for {} symbols using {} providers: {}",
                symbols.size(), providers.size(),
                providers.stream().map(PriceDataProvider::providerName).toList());

        int fetched = 0, skipped = 0, failed = 0;
        for (String symbol : symbols) {
            try {
                int saved = fetchAndPersistSymbol(symbol);
                fetched += saved;
                if (saved == 0) skipped++;
            } catch (Exception e) {
                log.warn("[PriceService] All providers failed for symbol={}: {}", symbol, e.getMessage());
                failed++;
            }

            // Rate limit courtesy pause between symbols
            if (interSymbolDelayMs > 0) {
                try { Thread.sleep(interSymbolDelayMs); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("[PriceService] Done. fetched={}, skipped(already up-to-date)={}, failed={}",
                fetched, skipped, failed);

        // Sync latest prices back to Investment records and rebuild portfolio snapshot
        updateInvestmentCurrentPrices(userId);
        rebuildPortfolioSnapshot(userId);
    }

    /**
     * For every active investment that has a symbol, look up the most recent close price
     * from StockPriceHistory and write it back to the Investment record
     * (currentPrice, currentValue, unrealizedGainLoss, gainLossPercentage).
     */
    @Transactional
    public void updateInvestmentCurrentPrices(String userId) {
        List<Investment> investments = investmentRepo.findActiveInvestments(userId);
        int updated = 0;
        for (Investment inv : investments) {
            if (inv.getSymbol() == null || inv.getSymbol().isBlank()) continue;
            Optional<StockPriceHistory> latest = priceRepo
                    .findTopBySymbolOrderByPriceDateDesc(inv.getSymbol().toUpperCase());
            if (latest.isEmpty() || latest.get().getClosePrice() == null) continue;

            BigDecimal latestClose = latest.get().getClosePrice();
            inv.setCurrentPrice(latestClose);

            if (inv.getQuantity() != null) {
                BigDecimal currentVal = latestClose.multiply(inv.getQuantity())
                        .setScale(4, RoundingMode.HALF_UP);
                inv.setCurrentValue(currentVal);

                if (inv.getTotalCost() != null && inv.getTotalCost().compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal pnl = currentVal.subtract(inv.getTotalCost());
                    inv.setUnrealizedGainLoss(pnl);
                    BigDecimal pnlPct = pnl.divide(inv.getTotalCost(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(4, RoundingMode.HALF_UP);
                    inv.setGainLossPercentage(pnlPct);
                }
            }

            investmentRepo.save(inv);
            updated++;
        }
        log.info("[PriceService] Updated current prices for {} investment records", updated);
    }

    /**
     * Compute a fresh PortfolioSnapshot from current Investment values and persist it.
     * Called after every price fetch cycle so the snapshot always reflects live prices.
     */
    @Transactional
    public void rebuildPortfolioSnapshot(String userId) {
        List<Investment> investments = investmentRepo.findActiveInvestments(userId);
        if (investments.isEmpty()) return;

        BigDecimal totalInvested = investments.stream()
                .filter(i -> i.getTotalCost() != null)
                .map(Investment::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal currentValue = investments.stream()
                .filter(i -> i.getCurrentValue() != null)
                .map(Investment::getCurrentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unrealizedPnl = currentValue.subtract(totalInvested);

        BigDecimal overallPnlPct = BigDecimal.ZERO;
        if (totalInvested.compareTo(BigDecimal.ZERO) > 0) {
            overallPnlPct = unrealizedPnl.divide(totalInvested, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        // Day P&L: sum of (quantity × priceChangePercent/100 × prevClose) for each holding
        BigDecimal dayPnl = BigDecimal.ZERO;
        for (Investment inv : investments) {
            if (inv.getSymbol() == null || inv.getQuantity() == null) continue;
            Optional<StockPriceHistory> latest = priceRepo
                    .findTopBySymbolOrderByPriceDateDesc(inv.getSymbol().toUpperCase());
            if (latest.isEmpty() || latest.get().getPriceChangePercent() == null
                    || latest.get().getClosePrice() == null) continue;

            double changePct = latest.get().getPriceChangePercent();
            BigDecimal prevClose = latest.get().getClosePrice()
                    .divide(BigDecimal.ONE.add(BigDecimal.valueOf(changePct / 100)), 6, RoundingMode.HALF_UP);
            BigDecimal dayMove = latest.get().getClosePrice().subtract(prevClose)
                    .multiply(inv.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);
            dayPnl = dayPnl.add(dayMove);
        }

        BigDecimal dayPnlPct = BigDecimal.ZERO;
        if (currentValue.compareTo(BigDecimal.ZERO) > 0) {
            dayPnlPct = dayPnl.divide(currentValue.subtract(dayPnl).max(BigDecimal.ONE), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                .userId(userId)
                .snapshotTime(LocalDateTime.now())
                .source("PRICE_FETCH")
                .totalInvested(totalInvested)
                .currentValue(currentValue)
                .unrealizedPnl(unrealizedPnl)
                .overallPnlPercent(overallPnlPct)
                .dayPnl(dayPnl)
                .dayPnlPercent(dayPnlPct)
                .holdingsCount(investments.size())
                .build();

        snapshotRepo.save(snapshot);
        log.info("[PriceService] Portfolio snapshot rebuilt: invested=₹{}, current=₹{}, pnl=₹{} ({}%)",
                totalInvested, currentValue, unrealizedPnl, overallPnlPct);
    }

    /**
     * Fetch price history for a single symbol, trying providers in fallback order.
     *
     * @return number of new rows saved (0 if all dates already stored)
     */
    @Transactional
    public int fetchAndPersistSymbol(String symbol) throws PriceProviderException {
        List<DailyPrice> prices = fetchWithFallback(symbol);
        if (prices.isEmpty()) return 0;

        int saved = 0;
        for (int i = 0; i < prices.size(); i++) {
            DailyPrice dp = prices.get(i);

            // Skip if already stored
            if (priceRepo.existsBySymbolAndPriceDate(symbol, dp.date())) continue;

            // Compute day-over-day price change
            Double changePercent = null;
            if (i > 0 && prices.get(i - 1).close() != null && dp.close() != null) {
                BigDecimal prev = prices.get(i - 1).close();
                if (prev.compareTo(BigDecimal.ZERO) != 0) {
                    changePercent = dp.close().subtract(prev)
                            .divide(prev, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .doubleValue();
                }
            } else if (i == 0) {
                // For the first data point, try to get the previous close from DB
                Optional<StockPriceHistory> prevStored = priceRepo
                        .findTopBySymbolOrderByPriceDateDesc(symbol);
                if (prevStored.isPresent() && prevStored.get().getClosePrice() != null
                        && dp.close() != null) {
                    BigDecimal prev = prevStored.get().getClosePrice();
                    if (prev.compareTo(BigDecimal.ZERO) != 0) {
                        changePercent = dp.close().subtract(prev)
                                .divide(prev, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    }
                }
            }

            // ── Data quality / split detection ────────────────────────────────────
            // A raw-close drop beyond SPLIT_SUSPECT_THRESHOLD is almost certainly a
            // corporate action (split / bonus), not a real lower-circuit day.
            // Flag it as SUSPECT_GAP so return-series computation excludes this point.
            DataQualityFlag qualityFlag = DataQualityFlag.OK;
            if (changePercent != null && changePercent <= SPLIT_SUSPECT_THRESHOLD_PERCENT) {
                qualityFlag = DataQualityFlag.SUSPECT_GAP;
                log.warn("[PriceService] SUSPECT_GAP: symbol={}, date={}, rawChange={}% — possible split/adj error",
                        symbol, dp.date(), String.format("%.1f", changePercent));
            }

            // ── Circuit breaker detection (skip if already flagged as suspect) ────
            boolean hitUpper = false, hitLower = false;
            if (qualityFlag == DataQualityFlag.OK && changePercent != null) {
                if (changePercent >= CIRCUIT_THRESHOLD_PERCENT)  hitUpper = true;
                if (changePercent <= -CIRCUIT_THRESHOLD_PERCENT) hitLower = true;
            }
            // Secondary heuristic: high == low (frozen price) — direction by close vs open
            if (qualityFlag == DataQualityFlag.OK
                    && dp.high() != null && dp.low() != null
                    && dp.high().compareTo(dp.low()) == 0
                    && dp.open() != null && dp.close() != null) {
                if (dp.close().compareTo(dp.open()) > 0)      hitUpper = true;
                else if (dp.close().compareTo(dp.open()) < 0) hitLower = true;
            }

            // ── Consecutive circuit count ─────────────────────────────────────────
            int consecutiveCount = 0;
            if (hitUpper || hitLower) {
                final boolean finalHitUpper = hitUpper;
                final boolean finalHitLower = hitLower;
                consecutiveCount = priceRepo.findTopBySymbolOrderByPriceDateDesc(symbol)
                        .map(prev -> {
                            boolean prevUpper = Boolean.TRUE.equals(prev.getHitUpperCircuit());
                            boolean prevLower = Boolean.TRUE.equals(prev.getHitLowerCircuit());
                            int prevCount = prev.getConsecutiveCircuitCount() != null
                                    ? prev.getConsecutiveCircuitCount() : 0;
                            if ((finalHitUpper && prevUpper) || (finalHitLower && prevLower)) {
                                return prevCount + 1;
                            }
                            return 1;
                        })
                        .orElse(1);
            }

            StockPriceHistory record = StockPriceHistory.builder()
                    .symbol(symbol)
                    .priceDate(dp.date())
                    .openPrice(dp.open())
                    .highPrice(dp.high())
                    .lowPrice(dp.low())
                    .closePrice(dp.close())
                    .adjustedClose(dp.adjClose())
                    .volume(dp.volume())
                    .priceChangePercent(changePercent)
                    .dataQualityFlag(qualityFlag)
                    .hitUpperCircuit(hitUpper)
                    .hitLowerCircuit(hitLower)
                    .consecutiveCircuitCount(consecutiveCount)
                    .build();

            priceRepo.save(record);
            saved++;

            if (hitUpper || hitLower) {
                log.info("[PriceService] Circuit detected: symbol={}, date={}, direction={}, consecutive={}",
                        symbol, dp.date(), hitUpper ? "UPPER" : "LOWER", consecutiveCount);
            }
        }

        log.debug("[PriceService] symbol={} — {} new records saved", symbol, saved);
        return saved;
    }

    /**
     * Tries each provider in the fallback chain until one returns data.
     * Throws PriceProviderException only if ALL providers fail.
     */
    private List<DailyPrice> fetchWithFallback(String symbol) throws PriceProviderException {
        PriceProviderException lastException = null;

        for (PriceDataProvider provider : providers) {
            try {
                log.debug("[PriceService] Trying provider={} for symbol={}", provider.providerName(), symbol);
                List<DailyPrice> result = provider.fetchHistory(symbol, historyDays);
                if (!result.isEmpty()) {
                    log.debug("[PriceService] provider={} succeeded for symbol={} ({} days)",
                            provider.providerName(), symbol, result.size());
                    return result;
                }
                log.debug("[PriceService] provider={} returned empty for symbol={}",
                        provider.providerName(), symbol);
            } catch (PriceProviderException e) {
                log.warn("[PriceService] provider={} failed for symbol={}: {}",
                        provider.providerName(), symbol, e.getMessage());
                lastException = e;
            }
        }

        String providerNames = providers.stream()
                .map(PriceDataProvider::providerName)
                .toList().toString();
        throw new PriceProviderException(
                "All providers " + providerNames + " failed for symbol=" + symbol,
                lastException);
    }

    // ── Benchmark ingestion ───────────────────────────────────────────────────

    /**
     * Fetches and persists Nifty 50 (^NSEI) daily price history for use as a benchmark
     * in beta and tracking-error computation. Fetches up to {@code days} calendar days
     * and uses the same UPSERT semantics as fetchAndPersistSymbol (skips existing dates).
     *
     * Called daily by CFOScheduler alongside the regular price fetch.
     *
     * @param days number of calendar days to request (e.g. 365 for one year of risk history)
     * @return number of new rows saved
     */
    @Transactional
    public int fetchAndPersistBenchmark(int days) {
        log.info("[PriceService] Fetching benchmark {} ({} days)", NIFTY_SYMBOL, days);
        try {
            // Temporarily override historyDays for this call
            List<DailyPrice> prices = fetchWithFallbackOverride(NIFTY_SYMBOL, days);
            if (prices.isEmpty()) {
                log.warn("[PriceService] No data returned for benchmark {}", NIFTY_SYMBOL);
                return 0;
            }

            int saved = 0;
            for (int i = 0; i < prices.size(); i++) {
                DailyPrice dp = prices.get(i);
                if (priceRepo.existsBySymbolAndPriceDate(NIFTY_SYMBOL, dp.date())) continue;

                Double changePercent = null;
                if (i > 0 && prices.get(i - 1).close() != null && dp.close() != null) {
                    BigDecimal prev = prices.get(i - 1).close();
                    if (prev.compareTo(BigDecimal.ZERO) != 0) {
                        changePercent = dp.close().subtract(prev)
                                .divide(prev, 6, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100))
                                .doubleValue();
                    }
                }

                StockPriceHistory record = StockPriceHistory.builder()
                        .symbol(NIFTY_SYMBOL)
                        .priceDate(dp.date())
                        .openPrice(dp.open())
                        .highPrice(dp.high())
                        .lowPrice(dp.low())
                        .closePrice(dp.close())
                        .adjustedClose(dp.adjClose() != null ? dp.adjClose() : dp.close())
                        .volume(dp.volume())
                        .priceChangePercent(changePercent)
                        .dataQualityFlag(DataQualityFlag.OK)
                        .build();

                priceRepo.save(record);
                saved++;
            }

            log.info("[PriceService] Benchmark {}: {} new records saved", NIFTY_SYMBOL, saved);
            return saved;

        } catch (PriceProviderException e) {
            log.error("[PriceService] Failed to fetch benchmark {}: {}", NIFTY_SYMBOL, e.getMessage());
            return 0;
        }
    }

    /**
     * Like fetchWithFallback but allows overriding the number of days (bypasses historyDays field).
     * Used for benchmark and risk-history pulls that need longer look-back periods.
     */
    private List<DailyPrice> fetchWithFallbackOverride(String symbol, int days)
            throws PriceProviderException {
        PriceProviderException lastException = null;
        for (PriceDataProvider provider : providers) {
            try {
                List<DailyPrice> result = provider.fetchHistory(symbol, days);
                if (!result.isEmpty()) return result;
            } catch (PriceProviderException e) {
                log.warn("[PriceService] provider={} failed for symbol={}: {}",
                        provider.providerName(), symbol, e.getMessage());
                lastException = e;
            }
        }
        throw new PriceProviderException(
                "All providers failed for symbol=" + symbol, lastException);
    }

    // ── Utility methods used by other services ────────────────────────────────

    /**
     * Returns the consecutive circuit count for the most recent trading day.
     * Used by PersonalizedRelevanceScorer for anomaly detection.
     */
    public int getConsecutiveCircuitCount(String symbol) {
        return priceRepo.findTopBySymbolOrderByPriceDateDesc(symbol)
                .map(h -> h.getConsecutiveCircuitCount() != null ? h.getConsecutiveCircuitCount() : 0)
                .orElse(0);
    }

    /**
     * Returns average absolute daily price change % over the last N days.
     * Used by MarketContextService for priceBasedVolatility.
     */
    public double getAvgDailyVolatility(String symbol, int days) {
        LocalDate since = LocalDate.now().minusDays(days);
        return priceRepo.findRecentBySymbol(symbol, since).stream()
                .filter(h -> h.getPriceChangePercent() != null)
                .mapToDouble(h -> Math.abs(h.getPriceChangePercent()))
                .average()
                .orElse(0.0);
    }

    /**
     * Returns the names of all configured providers in fallback order.
     * Used by the health/status endpoint for diagnostics.
     */
    public List<String> getProviderChain() {
        return providers.stream().map(PriceDataProvider::providerName).toList();
    }
}
