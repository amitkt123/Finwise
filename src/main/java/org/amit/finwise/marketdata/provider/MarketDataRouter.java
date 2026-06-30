package org.amit.finwise.marketdata.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class MarketDataRouter {

    private final List<MarketDataProvider> providers;
    private final CircuitBreakerRegistry cbRegistry;

    public MarketDataRouter(List<MarketDataProvider> providers, CircuitBreakerRegistry cbRegistry) {
        this.providers = providers;
        this.cbRegistry = cbRegistry;
    }

    public Optional<MarketDataProvider> healthyProvider(DataCapability capability) {
        return providers.stream()
            .filter(p -> p.supports(capability))
            .filter(MarketDataProvider::isHealthy)
            .filter(p -> cbRegistry.circuitBreaker(p.name()).getState()
                != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN)
            .findFirst();
    }

    public <T> DataEnvelope<T> route(DataCapability capability, java.util.function.Function<MarketDataProvider, DataEnvelope<T>> fetcher) {
        return healthyProvider(capability)
            .map(p -> {
                try {
                    return cbRegistry.circuitBreaker(p.name())
                        .executeSupplier(() -> fetcher.apply(p));
                } catch (Exception e) {
                    log.warn("[MarketDataRouter] {} failed for {}: {}", p.name(), capability, e.getMessage());
                    return DataEnvelope.<T>missing(p.name(), e.getMessage());
                }
            })
            .orElseGet(() -> DataEnvelope.missing("none", "No healthy provider for " + capability));
    }

    public boolean isHealthy(String providerName) {
        return cbRegistry.circuitBreaker(providerName).getState()
            != io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }
}
