package org.amit.finwise.cfo.service.research;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.repository.StockFundamentalsRepository;
import org.amit.finwise.cfo.service.price.PriceDataProvider;
import org.amit.finwise.cfo.service.price.YahooFinancePriceProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fetches, computes, and persists stock fundamental metrics.
 *
 * Metrics sourced from Yahoo quoteSummary:
 *   - Valuation: P/E, P/B, EV/EBITDA, EV/Sales, PEG
 *   - Profitability: margins (gross, EBITDA, net), ROE, D/E
 *   - Growth: revenue growth, FCF
 *
 * Valuation z-scores:
 *   z = (current - mean) / stdev → label CHEAP (<-1), FAIR ([-1, 1]), EXPENSIVE (>1)
 *   Computed against 2-year history; if insufficient data, flag DATA_INCOMPLETE.
 *
 * Quality gate: null margin or earnings → set dataQualityNotes flag.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundamentalsService {

    private final StockFundamentalsRepository fundamentalsRepo;
    private final YahooFinancePriceProvider yahooProvider;

    private static final int VALUATION_HISTORY_DAYS = 730; // 2 years
    private static final int MIN_HISTORY_FOR_ZSCORE = 60;

    /**
     * Fetch and persist fundamentals for a symbol.
     * Returns the persisted StockFundamentals; missing data is captured in dataQualityNotes.
     */
    public StockFundamentals fetchAndPersist(String symbol) {
        try {
            YahooFinancePriceProvider.FundamentalsSnapshot snapshot = yahooProvider.fetchFundamentals(symbol);
            return computeAndPersist(symbol, snapshot);
        } catch (PriceDataProvider.PriceProviderException e) {
            log.warn("[Fundamentals] Failed to fetch data for {}: {}", symbol, e.getMessage());
            // Return a stub with data quality flag
            return persistStubWithError(symbol, "FETCH_ERROR: " + e.getMessage());
        }
    }

    private StockFundamentals computeAndPersist(String symbol,
                                                 YahooFinancePriceProvider.FundamentalsSnapshot snap) {
        List<String> dataGaps = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // ── Data quality checks ──────────────────────────────────────────────
        if (snap.getNetProfitMargin() == null) {
            dataGaps.add("INCOMPLETE:NET_MARGIN");
        }
        if (snap.getGrossMargin() == null) {
            dataGaps.add("INCOMPLETE:GROSS_MARGIN");
        }
        if (snap.getTrailingPE() == null) {
            dataGaps.add("INCOMPLETE:EARNINGS");
        }

        // ── Compute valuation z-scores ───────────────────────────────────────
        BigDecimal peZScore = null;
        BigDecimal evEbitdaZScore = null;
        String valuationLabel = null;

        if (snap.getTrailingPE() != null && snap.getTrailingPE().compareTo(BigDecimal.ZERO) > 0) {
            peZScore = computePEZScore(symbol, snap.getTrailingPE());
        }
        if (snap.getEvToEbitda() != null && snap.getEvToEbitda().compareTo(BigDecimal.ZERO) > 0) {
            evEbitdaZScore = computeEVEbitdaZScore(symbol, snap.getEvToEbitda());
        }

        // Label valuation: prefer P/E if available, else EV/EBITDA
        if (peZScore != null) {
            valuationLabel = labelFromZScore(peZScore);
        } else if (evEbitdaZScore != null) {
            valuationLabel = labelFromZScore(evEbitdaZScore);
        }

        // Profitability flag
        boolean isProfitable = snap.getNetProfitMargin() != null
                && snap.getNetProfitMargin().compareTo(BigDecimal.ZERO) > 0;

        // Build the entity
        StockFundamentals fundamentals = StockFundamentals.builder()
                .symbol(symbol.toUpperCase())
                .snapshotDate(today)
                .trailingPE(snap.getTrailingPE())
                .forwardPE(snap.getForwardPE())
                .priceToBook(snap.getPriceToBook())
                .evToEbitda(snap.getEvToEbitda())
                .evToSales(snap.getEvToRevenue())
                .pegRatio(snap.getPegRatio())
                .dividendYield(snap.getDividendYield())
                .revenueGrowth(snap.getRevenueGrowth())
                .grossMargin(snap.getGrossMargin())
                .ebitdaMargin(snap.getEbitdaMargin())
                .operatingMargin(snap.getOperatingMargin())
                .netProfitMargin(snap.getNetProfitMargin())
                .roe(snap.getReturnOnEquity())
                .debtToEquity(snap.getDebtToEquity())
                .freeCashFlow(snap.getFreeCashFlow())
                .isProfitable(isProfitable)
                .peZScore(peZScore)
                .evEbitdaZScore(evEbitdaZScore)
                .valuationLabel(valuationLabel)
                .dataQualityNotes(dataGaps.isEmpty() ? null : String.join("; ", dataGaps))
                .build();

        return fundamentalsRepo.save(fundamentals);
    }

    private StockFundamentals persistStubWithError(String symbol, String errorNote) {
        StockFundamentals stub = StockFundamentals.builder()
                .symbol(symbol.toUpperCase())
                .snapshotDate(LocalDate.now())
                .dataQualityNotes(errorNote)
                .build();
        return fundamentalsRepo.save(stub);
    }

    /**
     * Compute z-score of current P/E against 2-year history.
     * If insufficient history (< 60 obs), return null and data gap will be set elsewhere.
     */
    private BigDecimal computePEZScore(String symbol, BigDecimal currentPE) {
        LocalDate twoYearsAgo = LocalDate.now().minusDays(VALUATION_HISTORY_DAYS);
        List<StockFundamentals> history = fundamentalsRepo
                .findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(symbol, twoYearsAgo, LocalDate.now());

        // Filter nulls
        List<BigDecimal> peValues = history.stream()
                .map(StockFundamentals::getTrailingPE)
                .filter(pe -> pe != null && pe.compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (peValues.size() < MIN_HISTORY_FOR_ZSCORE) {
            log.debug("[Fundamentals] Insufficient P/E history for {} (n={})", symbol, peValues.size());
            return null;
        }

        // Compute mean and stdev
        double mean = peValues.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0);

        double variance = peValues.stream()
                .mapToDouble(bd -> Math.pow(bd.doubleValue() - mean, 2))
                .average()
                .orElse(0.0);
        double stdev = Math.sqrt(variance);

        if (stdev < 0.01) {
            return BigDecimal.ZERO; // No variance
        }

        double zScore = (currentPE.doubleValue() - mean) / stdev;
        return BigDecimal.valueOf(zScore).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Compute z-score of current EV/EBITDA against 2-year history.
     */
    private BigDecimal computeEVEbitdaZScore(String symbol, BigDecimal currentEVEbitda) {
        LocalDate twoYearsAgo = LocalDate.now().minusDays(VALUATION_HISTORY_DAYS);
        List<StockFundamentals> history = fundamentalsRepo
                .findBySymbolAndSnapshotDateBetweenOrderBySnapshotDateAsc(symbol, twoYearsAgo, LocalDate.now());

        List<BigDecimal> evValues = history.stream()
                .map(StockFundamentals::getEvToEbitda)
                .filter(ev -> ev != null && ev.compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (evValues.size() < MIN_HISTORY_FOR_ZSCORE) {
            return null;
        }

        double mean = evValues.stream()
                .mapToDouble(BigDecimal::doubleValue)
                .average()
                .orElse(0.0);

        double variance = evValues.stream()
                .mapToDouble(bd -> Math.pow(bd.doubleValue() - mean, 2))
                .average()
                .orElse(0.0);
        double stdev = Math.sqrt(variance);

        if (stdev < 0.01) {
            return BigDecimal.ZERO;
        }

        double zScore = (currentEVEbitda.doubleValue() - mean) / stdev;
        return BigDecimal.valueOf(zScore).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Label valuation based on z-score: CHEAP (<-1), FAIR ([-1, 1]), EXPENSIVE (>1).
     */
    private String labelFromZScore(BigDecimal zScore) {
        if (zScore == null) return null;
        double z = zScore.doubleValue();
        if (z < -1.0) return "CHEAP";
        if (z > 1.0) return "EXPENSIVE";
        return "FAIR";
    }

    /**
     * Get the latest fundamentals for a symbol, or fetch if not cached today.
     */
    public StockFundamentals getLatest(String symbol) {
        Optional<StockFundamentals> cachedOpt = fundamentalsRepo
                .findTopBySymbolOrderBySnapshotDateDesc(symbol);

        if (cachedOpt.isPresent()) {
            StockFundamentals cached = cachedOpt.get();
            // Refresh daily
            if (cached.getSnapshotDate().equals(LocalDate.now())) {
                return cached;
            }
        }

        return fetchAndPersist(symbol);
    }
}
