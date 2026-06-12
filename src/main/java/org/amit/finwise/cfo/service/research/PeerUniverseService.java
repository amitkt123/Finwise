package org.amit.finwise.cfo.service.research;

import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.StockFundamentals;
import org.amit.finwise.cfo.repository.StockFundamentalsRepository;
import org.amit.finwise.cfo.service.analytics.PercentileUtil;
import org.amit.finwise.cfo.service.ingestion.SymbolExtractorService;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cross-sectional peer valuation from the symbol gazetteer — no paid feed.
 *
 * The peer group for a stock is every gazetteer symbol sharing its SECTOR tag.
 * Each peer's latest persisted {@link StockFundamentals} supplies trailing P/E
 * and P/B; the target's Hazen percentile rank within those values populates the
 * previously-dead {@code peerPePercentile}/{@code peerPbPercentile} fields that
 * the scorecard's relative-valuation branch consumes.
 *
 * Relative valuation is far more robust than own-history z-scores for cyclicals
 * (a steel stock at P/E 5 looks "cheap" vs its own history exactly at the cycle
 * top) and is the only option for IPOs with <2 years of history.
 *
 * Loss-making peers are excluded from the P/E ranking (a negative P/E is not
 * "cheap") and the count is disclosed. Fewer than {@code MIN_PEERS} valid peers
 * leaves the fields null — a thin universe rank is false precision.
 *
 * The weekly refresh walks held sectors' peers through the daily-cached
 * FundamentalsService Yahoo path, honoring the inter-symbol delay.
 */
@Slf4j
@Service
public class PeerUniverseService implements MarketDataProvider.FundamentalsProvider {

    private final SymbolExtractorService symbolExtractorService;
    private final StockFundamentalsRepository fundamentalsRepo;
    private final FundamentalsService fundamentalsService;
    private final InvestmentRepository investmentRepository;
    private final long interSymbolDelayMs;

    public PeerUniverseService(SymbolExtractorService symbolExtractorService,
                               StockFundamentalsRepository fundamentalsRepo,
                               FundamentalsService fundamentalsService,
                               InvestmentRepository investmentRepository,
                               @Value("${cfo.price.inter-symbol-delay-ms:500}") long interSymbolDelayMs) {
        this.symbolExtractorService = symbolExtractorService;
        this.fundamentalsRepo = fundamentalsRepo;
        this.fundamentalsService = fundamentalsService;
        this.investmentRepository = investmentRepository;
        this.interSymbolDelayMs = interSymbolDelayMs;
    }

    private static final int MIN_PEERS = 5;

    @Override
    public String providerName() {
        return "gazetteer-peer-universe";
    }

    @Override
    public boolean supportsNormalizedPeers() {
        return true;
    }

    /**
     * Populates peer percentiles on the given fundamentals snapshot from peers'
     * already-persisted data (no network). Sector resolves via the gazetteer.
     * Returns the (possibly updated) entity; fields stay null when the universe
     * is too thin.
     */
    public StockFundamentals applyPercentiles(StockFundamentals fundamentals) {
        if (fundamentals == null || fundamentals.getSymbol() == null) return fundamentals;
        SymbolExtractorService.SymbolEntry entry =
                symbolExtractorService.getSymbolEntry(fundamentals.getSymbol());
        if (entry == null || entry.sector == null) return fundamentals;
        return applyPercentiles(fundamentals, entry.sector);
    }

    public StockFundamentals applyPercentiles(StockFundamentals fundamentals, String sector) {
        List<String> peers = symbolExtractorService.symbolsInSector(sector);
        if (peers.size() < MIN_PEERS) return fundamentals;

        Map<String, StockFundamentals> latestBySymbol = latestSnapshots(peers);
        latestBySymbol.put(fundamentals.getSymbol().toUpperCase(), fundamentals);

        List<Double> peValues = new ArrayList<>();
        List<Double> pbValues = new ArrayList<>();
        int lossMaking = 0;
        for (StockFundamentals f : latestBySymbol.values()) {
            BigDecimal pe = f.getTrailingPE();
            if (pe != null) {
                if (pe.signum() > 0) peValues.add(pe.doubleValue());
                else lossMaking++;
            }
            BigDecimal pb = f.getPriceToBook();
            if (pb != null && pb.signum() > 0) pbValues.add(pb.doubleValue());
        }

        boolean changed = false;
        if (fundamentals.getTrailingPE() != null && fundamentals.getTrailingPE().signum() > 0
                && peValues.size() >= MIN_PEERS) {
            double pct = PercentileUtil.hazenPercentile(
                    fundamentals.getTrailingPE().doubleValue(), toArray(peValues));
            fundamentals.setPeerPePercentile(round1(pct));
            changed = true;
        }
        if (fundamentals.getPriceToBook() != null && fundamentals.getPriceToBook().signum() > 0
                && pbValues.size() >= MIN_PEERS) {
            double pct = PercentileUtil.hazenPercentile(
                    fundamentals.getPriceToBook().doubleValue(), toArray(pbValues));
            fundamentals.setPeerPbPercentile(round1(pct));
            changed = true;
        }
        if (changed) {
            fundamentals.setPeerGroup(String.format("%s (gazetteer, %d peers%s)",
                    sector, latestBySymbol.size() - 1,
                    lossMaking > 0 ? ", " + lossMaking + " loss-making excluded from P/E" : ""));
            return fundamentalsRepo.save(fundamentals);
        }
        return fundamentals;
    }

    /**
     * Weekly warm-up: fetch (daily-cached) fundamentals for every peer of every
     * sector the user holds, then recompute the held symbols' percentiles.
     * Rate-limited by the configured inter-symbol delay.
     */
    public void refreshForUser(String userId) {
        List<Investment> holdings = investmentRepository.findActiveInvestments(userId);
        Set<String> sectors = new LinkedHashSet<>();
        for (Investment inv : holdings) {
            if (inv.getSymbol() == null) continue;
            SymbolExtractorService.SymbolEntry entry =
                    symbolExtractorService.getSymbolEntry(inv.getSymbol());
            if (entry != null && entry.sector != null) sectors.add(entry.sector);
        }

        for (String sector : sectors) {
            List<String> peers = symbolExtractorService.symbolsInSector(sector);
            if (peers.size() < MIN_PEERS) continue;
            log.info("[PeerUniverse] Refreshing {} peers for sector {}", peers.size(), sector);
            for (String peer : peers) {
                try {
                    fundamentalsService.getLatest(peer);
                } catch (Exception e) {
                    log.warn("[PeerUniverse] Peer fetch failed for {}: {}", peer, e.getMessage());
                }
                sleep();
            }
            for (String peer : peers) {
                fundamentalsRepo.findTopBySymbolOrderBySnapshotDateDesc(peer)
                        .ifPresent(f -> applyPercentiles(f, sector));
            }
        }
    }

    private Map<String, StockFundamentals> latestSnapshots(List<String> symbols) {
        Map<String, StockFundamentals> result = new LinkedHashMap<>();
        for (String sym : symbols) {
            Optional<StockFundamentals> latest =
                    fundamentalsRepo.findTopBySymbolOrderBySnapshotDateDesc(sym.toUpperCase());
            latest.ifPresent(f -> result.put(sym.toUpperCase(), f));
        }
        return result;
    }

    private static double[] toArray(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static BigDecimal round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    private void sleep() {
        try {
            Thread.sleep(interSymbolDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
