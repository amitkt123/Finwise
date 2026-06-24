package org.amit.finwise.marketdata.controller;

import lombok.RequiredArgsConstructor;
import org.amit.finwise.cfo.model.StockPriceHistory;
import org.amit.finwise.cfo.repository.StockPriceHistoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private static final Map<String, String> INDEX_LABELS = Map.of(
            "^NSEI", "NIFTY 50",
            "^BSESN", "SENSEX",
            "^NSEBANK", "BANKNIFTY"
    );

    private final StockPriceHistoryRepository priceRepo;

    @GetMapping("/tickers")
    public ResponseEntity<MarketTickerResponse> getTickers() {
        List<TickerEntry> tickers = new ArrayList<>();
        for (Map.Entry<String, String> entry : INDEX_LABELS.entrySet()) {
            Optional<StockPriceHistory> latest =
                    priceRepo.findTopBySymbolOrderByPriceDateDesc(entry.getKey());
            latest.ifPresent(h -> tickers.add(new TickerEntry(
                    entry.getValue(),
                    h.getClosePrice() != null ? h.getClosePrice() : BigDecimal.ZERO,
                    h.getPriceChangePercent()
            )));
        }
        return ResponseEntity.ok(new MarketTickerResponse(tickers));
    }

    record MarketTickerResponse(List<TickerEntry> tickers) {}
    record TickerEntry(String symbol, BigDecimal value, Double changePct) {}
}
