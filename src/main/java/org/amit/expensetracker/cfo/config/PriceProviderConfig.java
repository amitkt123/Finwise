package org.amit.expensetracker.cfo.config;

import org.amit.expensetracker.cfo.service.price.AlphaVantagePriceProvider;
import org.amit.expensetracker.cfo.service.price.NSEIndiaPriceProvider;
import org.amit.expensetracker.cfo.service.price.PriceDataProvider;
import org.amit.expensetracker.cfo.service.price.YahooFinancePriceProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the ordered list of PriceDataProvider instances used by StockPriceService.
 *
 * Order is determined by cfo.price.provider (primary) and cfo.price.fallback-providers (fallback list).
 * Providers whose API key is blank/unconfigured are skipped.
 *
 * Example configuration:
 *   cfo.price.provider=yahoo-finance
 *   cfo.price.fallback-providers=alpha-vantage,nse-india
 *   cfo.price.alpha-vantage.api-key=YOUR_KEY_HERE
 *
 * Fallback chain: StockPriceService will try each provider in order until one succeeds.
 */
@Configuration
public class PriceProviderConfig {

    @Value("${cfo.price.provider:yahoo-finance}")
    private String primaryProvider;

    @Value("${cfo.price.fallback-providers:alpha-vantage,nse-india}")
    private String fallbackProviders;

    @Value("${cfo.price.alpha-vantage.api-key:}")
    private String alphaVantageApiKey;

    /**
     * Returns providers in priority order: [primary, ...fallbacks].
     * Providers with missing API keys are excluded automatically.
     */
    @Bean
    public List<PriceDataProvider> priceDataProviders() {
        List<String> order = new ArrayList<>();
        order.add(primaryProvider.trim().toLowerCase());
        for (String fb : fallbackProviders.split(",")) {
            String name = fb.trim().toLowerCase();
            if (!name.isBlank() && !order.contains(name)) {
                order.add(name);
            }
        }

        List<PriceDataProvider> providers = new ArrayList<>();
        for (String name : order) {
            PriceDataProvider p = buildProvider(name);
            if (p != null) providers.add(p);
        }

        if (providers.isEmpty()) {
            // Always guarantee at least Yahoo Finance as a safety net
            providers.add(new YahooFinancePriceProvider());
        }

        return providers;
    }

    private PriceDataProvider buildProvider(String name) {
        return switch (name) {
            case "yahoo-finance", "yahoo" ->
                    new YahooFinancePriceProvider();

            case "alpha-vantage", "alphavantage" -> {
                if (alphaVantageApiKey == null || alphaVantageApiKey.isBlank()) {
                    yield null; // skip — no key configured
                }
                yield new AlphaVantagePriceProvider(alphaVantageApiKey);
            }

            case "nse-india", "nse" ->
                    new NSEIndiaPriceProvider();

            default -> {
                // Unknown provider name — log and skip
                org.slf4j.LoggerFactory.getLogger(PriceProviderConfig.class)
                        .warn("[PriceConfig] Unknown price provider '{}' — skipping", name);
                yield null;
            }
        };
    }
}
