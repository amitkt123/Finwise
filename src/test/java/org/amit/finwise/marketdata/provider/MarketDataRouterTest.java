package org.amit.finwise.marketdata.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataRouterTest {

    @Test
    void healthyProvider_returnsFirstSupportingAdapter() {
        MarketFeedProvider fred = new MarketFeedProvider() {
            public String name() { return "fred"; }
            public boolean supports(DataCapability c) { return c == DataCapability.MACRO_GLOBAL; }
            public boolean isHealthy() { return true; }
        };
        MarketDataRouter router = new MarketDataRouter(List.of(fred), CircuitBreakerRegistry.ofDefaults());
        Optional<MarketFeedProvider> found = router.healthyProvider(DataCapability.MACRO_GLOBAL);
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("fred");
    }

    @Test
    void healthyProvider_returnsEmptyWhenNoneSupport() {
        MarketDataRouter router = new MarketDataRouter(List.of(), CircuitBreakerRegistry.ofDefaults());
        assertThat(router.healthyProvider(DataCapability.REAL_TIME_QUOTE)).isEmpty();
    }

    @Test
    void healthyProvider_skipsUnhealthyAdapter() {
        MarketFeedProvider unhealthy = new MarketFeedProvider() {
            public String name() { return "broken"; }
            public boolean supports(DataCapability c) { return true; }
            public boolean isHealthy() { return false; }
        };
        MarketDataRouter router = new MarketDataRouter(List.of(unhealthy), CircuitBreakerRegistry.ofDefaults());
        assertThat(router.healthyProvider(DataCapability.MACRO_GLOBAL)).isEmpty();
    }

    @Test
    void isHealthy_reflectsCircuitBreakerState() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        MarketDataRouter router = new MarketDataRouter(List.of(), registry);

        assertThat(router.isHealthy("closedProvider")).isTrue();

        registry.circuitBreaker("openProvider").transitionToOpenState();
        assertThat(router.isHealthy("openProvider")).isFalse();
    }

    @Test
    void healthyProvider_skipsAdapterWithOpenCircuitBreaker() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        MarketFeedProvider tripped = new MarketFeedProvider() {
            public String name() { return "tripped"; }
            public boolean supports(DataCapability c) { return true; }
            public boolean isHealthy() { return true; }
        };
        MarketDataRouter router = new MarketDataRouter(List.of(tripped), registry);
        registry.circuitBreaker("tripped").transitionToOpenState();

        assertThat(router.healthyProvider(DataCapability.MACRO_GLOBAL)).isEmpty();
    }
}
