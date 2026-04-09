package org.amit.finwise.cfo.service.ingestion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.GrowwHolding;
import org.amit.finwise.cfo.model.GrowwHoldingsResponse;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.model.Transaction;
import org.amit.finwise.cfo.repository.PortfolioSnapshotRepository;
import org.amit.finwise.cfo.repository.TransactionRepository;
import org.amit.finwise.cfo.service.GrowwAuthService;
import org.amit.finwise.cfo.service.InvestorBehaviorService;
import org.amit.finwise.investment.enums.InvestmentType;
import org.amit.finwise.investment.model.Investment;
import org.amit.finwise.investment.repository.InvestmentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrowwConnector {

    private final GrowwAuthService growwAuthService;
    private final PortfolioSnapshotRepository snapshotRepository;
    private final TransactionRepository transactionRepository;
    private final InvestorBehaviorService investorBehaviorService;
    private final InvestmentRepository investmentRepository;

    @Value("${cfo.groww.base-url}")
    private String baseUrl;

    @Value("${cfo.groww.holdings-path}")
    private String holdingsPath;

    @Value("${cfo.groww.transactions-path}")
    private String transactionsPath;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    /**
     * Fetch current holdings from Groww, upsert into the investments table,
     * and save a portfolio snapshot.
     *
     * <p>Upsert logic:
     * <ul>
     *   <li>Holdings present in API response → created or updated in investments table</li>
     *   <li>Holdings previously synced from Groww but absent from this response → marked inactive (sold)</li>
     * </ul>
     */
    @Transactional
    public PortfolioSnapshot syncHoldings() {
        String userId = defaultUserId;
        String token = growwAuthService.getToken(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "No Groww token found. Update it via PUT /api/cfo/auth/groww/token"));

        try {
            GrowwHoldingsResponse response = buildClient(token)
                    .get()
                    .uri(holdingsPath)
                    .retrieve()
                    .body(GrowwHoldingsResponse.class);

            if (response == null || response.payload() == null) {
                log.warn("Groww holdings API returned empty response");
                return null;
            }

            // Groww returns "SUCCESS" (uppercase)
            if (!"success".equalsIgnoreCase(response.status())) {
                log.warn("Groww API returned non-success status: {}", response.status());
                return null;
            }

            growwAuthService.markTokenUsed(userId);

            List<GrowwHolding> holdings = response.payload().holdings();
            if (holdings == null) holdings = List.of();

            // ── Upsert each holding into the investments table ──────────────────
            int created = 0, updated = 0;
            for (GrowwHolding h : holdings) {
                if (h.symbol() == null || h.quantity() == null || h.avgPrice() == null) continue;

                BigDecimal totalCost = h.quantity().multiply(h.avgPrice())
                        .setScale(4, RoundingMode.HALF_UP);

                List<Investment> existing = investmentRepository.findBySymbol(userId, h.symbol());
                Investment inv = existing.isEmpty() ? null : existing.get(0);

                if (inv == null) {
                    inv = Investment.builder()
                            .userId(userId)
                            .symbol(h.symbol())
                            .name(h.symbol())           // display name = symbol; enriched later via price fetch
                            .platform("GROWW")
                            .type(resolveType(h.isin(), h.symbol()))
                            .sector(resolveSector(h.isin(), h.symbol()))
                            .purchaseDate(LocalDate.now()) // holdings API has no purchase date
                            .isActive(true)
                            .build();
                    created++;
                } else {
                    updated++;
                }

                inv.setQuantity(h.quantity());
                inv.setCostPerUnit(h.avgPrice().setScale(4, RoundingMode.HALF_UP));
                inv.setTotalCost(totalCost);
                inv.setIsActive(true);
                // currentPrice / currentValue / unrealizedGainLoss are populated
                // by StockPriceService (POST /api/cfo/prices/fetch) — not available here
                investmentRepository.save(inv);
            }

            // ── Mark removed holdings as inactive (position closed / transferred out) ─
            java.util.Set<String> liveSymbols = holdings.stream()
                    .map(GrowwHolding::symbol)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());

            investmentRepository.findActiveInvestments(userId).stream()
                    .filter(inv -> "GROWW".equals(inv.getPlatform())
                            && inv.getSymbol() != null
                            && !liveSymbols.contains(inv.getSymbol()))
                    .forEach(inv -> {
                        inv.setIsActive(false);
                        inv.setSaleDate(LocalDate.now());
                        investmentRepository.save(inv);
                        log.info("Marked {} as inactive (no longer in Groww holdings)", inv.getSymbol());
                    });

            log.info("Groww holdings upserted: {} created, {} updated", created, updated);

            // ── Portfolio snapshot (aggregate) ──────────────────────────────────
            BigDecimal totalInvested = holdings.stream()
                    .filter(h -> h.quantity() != null && h.avgPrice() != null)
                    .map(h -> h.quantity().multiply(h.avgPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                    .userId(userId)
                    .snapshotTime(LocalDateTime.now())
                    .source("GROWW")
                    .totalInvested(totalInvested)
                    .holdingsCount(holdings.size())
                    .build();

            snapshotRepository.save(snapshot);
            log.info("Groww snapshot saved: {} holdings, invested=₹{}", holdings.size(), totalInvested);

            investorBehaviorService.recomputeAsync(userId);

            return snapshot;

        } catch (HttpClientErrorException.Unauthorized e) {
            log.error("Groww token expired/invalid. Please update via PUT /api/cfo/auth/groww/token");
            throw new IllegalStateException("Groww token invalid. Please refresh it.", e);
        } catch (Exception e) {
            log.error("Failed to sync Groww holdings: {}", e.getMessage());
            throw new RuntimeException("Groww sync failed", e);
        }
    }

    // ── Type / Sector Resolution ──────────────────────────────────────────────

    /**
     * Derive InvestmentType from ISIN prefix and symbol suffix.
     * ISIN starting with INF = Mutual Fund / ETF (Indian fund instruments).
     * Symbol ending with BEES = ETF.
     */
    private InvestmentType resolveType(String isin, String symbol) {
        if (isin != null && isin.startsWith("INF")) {
            return (symbol != null && symbol.toUpperCase().endsWith("BEES"))
                    ? InvestmentType.ETF : InvestmentType.MUTUAL_FUND;
        }
        return InvestmentType.STOCK;
    }

    /**
     * Best-effort sector lookup by ISIN, then by symbol.
     * This list covers the most common Nifty 50 / large-cap names.
     * Add entries here as your portfolio grows.
     */
    private String resolveSector(String isin, String symbol) {
        if (isin != null) {
            String sector = ISIN_SECTOR.get(isin);
            if (sector != null) return sector;
        }
        if (symbol != null) {
            String sector = SYMBOL_SECTOR.get(symbol.toUpperCase());
            if (sector != null) return sector;
        }
        return null; // unknown; can be filled via InvestmentController later
    }

    /** ISIN → sector (covers Nifty 50 + popular ETFs). */
    private static final java.util.Map<String, String> ISIN_SECTOR = java.util.Map.ofEntries(
        java.util.Map.entry("INE040A01034", "Banking"),
        java.util.Map.entry("INE062A01020", "Banking"),
        java.util.Map.entry("INE009A01021", "IT"),
        java.util.Map.entry("INE467B01029", "IT"),
        java.util.Map.entry("INE081A01020", "Metals"),
        java.util.Map.entry("INE154A01025", "FMCG"),
        java.util.Map.entry("INE733E01010", "Energy"),
        java.util.Map.entry("INE758T01015", "FMCG"),        // Eternal (Zomato)
        java.util.Map.entry("INF204KB15I9", "Banking"),     // BANKBEES ETF
        java.util.Map.entry("INF204KC1402", "Gold"),        // SILVERBEES ETF
        java.util.Map.entry("INF277KA1984", "Gold"),        // TATSILV MF
        java.util.Map.entry("INE030A01027", "IT"),          // Wipro
        java.util.Map.entry("INE860A01027", "Pharma"),      // HCL Tech
        java.util.Map.entry("INE028A01039", "Banking"),     // ICICI Bank
        java.util.Map.entry("INE090A01021", "Banking"),     // Kotak Bank
        java.util.Map.entry("INE238A01034", "Auto"),        // Maruti
        java.util.Map.entry("INE721A01013", "Capital Markets"), // Shriram Finance
        java.util.Map.entry("INE414G01012", "Energy"),      // Adani Ports
        java.util.Map.entry("INE002A01018", "Energy"),      // Reliance
        java.util.Map.entry("INE001A01036", "Banking"),     // SBI (older ISIN)
        java.util.Map.entry("INE585B01010", "IT"),          // Infosys old
        java.util.Map.entry("INE066A01021", "Pharma")       // Sun Pharma
    );

    /** Symbol → sector fallback. */
    private static final java.util.Map<String, String> SYMBOL_SECTOR = java.util.Map.ofEntries(
        java.util.Map.entry("HDFCBANK",   "Banking"),
        java.util.Map.entry("SBIN",       "Banking"),
        java.util.Map.entry("ICICIBANK",  "Banking"),
        java.util.Map.entry("KOTAKBANK",  "Banking"),
        java.util.Map.entry("AXISBANK",   "Banking"),
        java.util.Map.entry("BANKBEES",   "Banking"),
        java.util.Map.entry("TCS",        "IT"),
        java.util.Map.entry("INFY",       "IT"),
        java.util.Map.entry("WIPRO",      "IT"),
        java.util.Map.entry("HCLTECH",    "IT"),
        java.util.Map.entry("TECHM",      "IT"),
        java.util.Map.entry("TATASTEEL",  "Metals"),
        java.util.Map.entry("JSWSTEEL",   "Metals"),
        java.util.Map.entry("HINDALCO",   "Metals"),
        java.util.Map.entry("ITC",        "FMCG"),
        java.util.Map.entry("HINDUNILVR", "FMCG"),
        java.util.Map.entry("NESTLEIND",  "FMCG"),
        java.util.Map.entry("ETERNAL",    "FMCG"),
        java.util.Map.entry("NTPC",       "Energy"),
        java.util.Map.entry("POWERGRID",  "Energy"),
        java.util.Map.entry("ONGC",       "Energy"),
        java.util.Map.entry("RELIANCE",   "Energy"),
        java.util.Map.entry("SILVERBEES", "Gold"),
        java.util.Map.entry("TATSILV",    "Gold"),
        java.util.Map.entry("GOLDBEES",   "Gold"),
        java.util.Map.entry("SUNPHARMA",  "Pharma"),
        java.util.Map.entry("DRREDDY",    "Pharma"),
        java.util.Map.entry("CIPLA",      "Pharma"),
        java.util.Map.entry("MARUTI",     "Auto"),
        java.util.Map.entry("TATAMOTORS", "Auto"),
        java.util.Map.entry("M&M",        "Auto"),
        java.util.Map.entry("INDIGO",     "Aviation"),
        java.util.Map.entry("INTERGLOBE", "Aviation"),
        java.util.Map.entry("DLF",        "Realty"),
        java.util.Map.entry("GODREJPROP", "Realty")
    );

    /**
     * Fetch transaction history from Groww and store in unified ledger.
     */
    @Transactional
    public int syncTransactions() {
        String userId = defaultUserId;
        String token = growwAuthService.getToken(userId)
                .orElseThrow(() -> new IllegalStateException("No Groww token found."));

        try {
            GrowwTransactionsResponse response = buildClient(token)
                    .get()
                    .uri(transactionsPath)
                    .retrieve()
                    .body(GrowwTransactionsResponse.class);

            if (response == null || response.transactions() == null) return 0;

            int saved = 0;
            for (GrowwTransaction gt : response.transactions()) {
                String hash = buildHash(userId, gt);
                if (transactionRepository.existsByDedupHash(hash)) continue;

                Transaction txn = Transaction.builder()
                        .userId(userId)
                        .transactionDate(parseDate(gt.date()))
                        .transactionType(mapType(gt.type()))
                        .source(Transaction.TransactionSource.GROWW)
                        .amount(orZero(gt.amount()))
                        .symbol(gt.symbol())
                        .name(gt.scriptName())
                        .quantity(orZero(gt.quantity()))
                        .pricePerUnit(orZero(gt.price()))
                        .description(gt.type() + " - " + gt.scriptName())
                        .referenceNumber(gt.orderId())
                        .dedupHash(hash)
                        .build();

                transactionRepository.save(txn);
                saved++;
            }

            log.info("Imported {} new Groww transactions", saved);
            return saved;

        } catch (Exception e) {
            log.error("Failed to sync Groww transactions: {}", e.getMessage());
            throw new RuntimeException("Groww transaction sync failed", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RestClient buildClient(String token) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .defaultHeader("X-API-VERSION", "1.0*")
                .build();
    }

    private String buildHash(String userId, GrowwTransaction gt) {
        String raw = userId + "|" + gt.date() + "|" + gt.type() + "|" + gt.amount() + "|" + gt.orderId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private BigDecimal orZero(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }

    private LocalDate parseDate(String dateStr) {
        if (dateStr == null) return LocalDate.now();
        try {
            return LocalDate.parse(dateStr);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private Transaction.TransactionType mapType(String growwType) {
        if (growwType == null) return Transaction.TransactionType.OTHER;
        return switch (growwType.toUpperCase()) {
            case "BUY"      -> Transaction.TransactionType.BUY;
            case "SELL"     -> Transaction.TransactionType.SELL;
            case "DIVIDEND" -> Transaction.TransactionType.DIVIDEND;
            default         -> Transaction.TransactionType.OTHER;
        };
    }

    // ── Transaction API DTOs (kept local — not yet extracted to model package) ─

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrowwTransactionsResponse(
            @JsonProperty("transactions") List<GrowwTransaction> transactions
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GrowwTransaction(
            @JsonProperty("orderId")    String orderId,
            @JsonProperty("symbol")     String symbol,
            @JsonProperty("scriptName") String scriptName,
            @JsonProperty("type")       String type,
            @JsonProperty("quantity")   BigDecimal quantity,
            @JsonProperty("price")      BigDecimal price,
            @JsonProperty("amount")     BigDecimal amount,
            @JsonProperty("date")       String date
    ) {}
}
