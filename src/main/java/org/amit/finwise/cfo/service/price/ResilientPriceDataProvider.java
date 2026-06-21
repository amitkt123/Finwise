package org.amit.finwise.cfo.service.price;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Wraps a PriceDataProvider with a per-provider circuit breaker and retry.
 *
 * The circuit opens after consecutive failures, causing fast-fail so StockPriceService
 * can fall through to the next provider without waiting for HTTP timeouts.
 */
@Slf4j
public class ResilientPriceDataProvider implements PriceDataProvider {

    private final PriceDataProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientPriceDataProvider(PriceDataProvider delegate,
                                      CircuitBreakerRegistry cbRegistry,
                                      RetryRegistry retryRegistry) {
        this.delegate = delegate;
        String name = "price-" + delegate.providerName();
        this.circuitBreaker = cbRegistry.circuitBreaker(name);
        this.retry = retryRegistry.retry(name);
    }

    @Override
    public List<DailyPrice> fetchHistory(String symbol, int days) throws PriceProviderException {
        try {
            return CircuitBreaker.decorateCheckedSupplier(
                    circuitBreaker,
                    Retry.decorateCheckedSupplier(
                            retry,
                            () -> delegate.fetchHistory(symbol, days)))
                    .get();
        } catch (PriceProviderException e) {
            throw e;
        } catch (Throwable e) {
            log.warn("[{}] Circuit breaker/retry failure for {}: {}", delegate.providerName(), symbol, e.getMessage());
            throw new PriceProviderException(delegate.providerName() + " unavailable: " + e.getMessage(), e);
        }
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public boolean requiresApiKey() {
        return delegate.requiresApiKey();
    }
}
