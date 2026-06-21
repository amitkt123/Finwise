package org.amit.finwise.cfo.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.amit.finwise.cfo.service.price.AlphaVantagePriceProvider;
import org.amit.finwise.cfo.service.price.NSEIndiaPriceProvider;
import org.amit.finwise.cfo.service.price.PriceDataProvider;
import org.amit.finwise.cfo.service.price.ResilientPriceDataProvider;
import org.amit.finwise.cfo.service.price.YahooFinancePriceProvider;
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
     * Single shared Yahoo Finance provider bean.
     *
     * Exposed as a standalone bean (not just an element of the priceDataProviders list)
     * because FundamentalsService and MacroStateService autowire it directly by type for
     * the quoteSummary / macro fetches. The fallback chain below reuses this same instance.
     */
    @Bean
    public YahooFinancePriceProvider yahooFinancePriceProvider() {
        return new YahooFinancePriceProvider();
    }

    /**
     * Returns providers in priority order: [primary, ...fallbacks].
     * Providers with missing API keys are excluded automatically.
     */
    @Bean
    public List<PriceDataProvider> priceDataProviders(YahooFinancePriceProvider yahooFinancePriceProvider,
                                                      org.amit.finwise.cfo.service.price.NseSessionManager nseSessionManager,
                                                      CircuitBreakerRegistry circuitBreakerRegistry,
                                                      RetryRegistry retryRegistry) {
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
            PriceDataProvider p = buildProvider(name, yahooFinancePriceProvider, nseSessionManager);
            if (p != null) {
                providers.add(new ResilientPriceDataProvider(p, circuitBreakerRegistry, retryRegistry));
            }
        }

        if (providers.isEmpty()) {
            providers.add(new ResilientPriceDataProvider(
                    yahooFinancePriceProvider, circuitBreakerRegistry, retryRegistry));
        }

        return providers;
    }

    private PriceDataProvider buildProvider(String name, YahooFinancePriceProvider yahooFinancePriceProvider,
                                            org.amit.finwise.cfo.service.price.NseSessionManager nseSessionManager) {
        return switch (name) {
            case "yahoo-finance", "yahoo" ->
                    yahooFinancePriceProvider;

            case "alpha-vantage", "alphavantage" -> {
                if (alphaVantageApiKey == null || alphaVantageApiKey.isBlank()) {
                    yield null; // skip — no key configured
                }
                yield new AlphaVantagePriceProvider(alphaVantageApiKey);
            }

            case "nse-india", "nse" ->
                    new NSEIndiaPriceProvider(nseSessionManager);

            default -> {
                // Unknown provider name — log and skip
                org.slf4j.LoggerFactory.getLogger(PriceProviderConfig.class)
                        .warn("[PriceConfig] Unknown price provider '{}' — skipping", name);
                yield null;
            }
        };
    }
}
