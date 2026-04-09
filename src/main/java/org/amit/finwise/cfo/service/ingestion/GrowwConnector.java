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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
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

    @Value("${cfo.groww.base-url}")
    private String baseUrl;

    @Value("${cfo.groww.holdings-path}")
    private String holdingsPath;

    @Value("${cfo.groww.transactions-path}")
    private String transactionsPath;

    @Value("${cfo.user.id}")
    private String defaultUserId;

    /**
     * Fetch current holdings from Groww and save a portfolio snapshot.
     * Portfolio-level totals (invested, P&L) are computed from individual holdings
     * since the API returns per-holding data only.
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

            if (!"success".equalsIgnoreCase(response.status())) {
                log.warn("Groww API returned non-success status: {}", response.status());
                return null;
            }

            growwAuthService.markTokenUsed(userId);

            List<GrowwHolding> holdings = response.payload().holdings();
            if (holdings == null) holdings = List.of();

            // Compute total invested from individual holdings (quantity × avgPrice)
            BigDecimal totalInvested = holdings.stream()
                    .filter(h -> h.quantity() != null && h.avgPrice() != null)
                    .map(h -> h.quantity().multiply(h.avgPrice()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                    .userId(userId)
                    .snapshotTime(LocalDateTime.now())
                    .source("GROWW")
                    .totalInvested(totalInvested)
                    // currentValue / P&L not available from holdings endpoint alone
                    // (no LTP returned). Will be null until a price-enrichment step is added.
                    .currentValue(null)
                    .unrealizedPnl(null)
                    .dayPnl(null)
                    .dayPnlPercent(null)
                    .overallPnlPercent(null)
                    .holdingsCount(holdings.size())
                    .build();

            snapshotRepository.save(snapshot);
            log.info("Groww snapshot saved: {} holdings, invested=₹{}", holdings.size(), totalInvested);

            // Trigger async behavior recompute so profile stays fresh after each sync
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
