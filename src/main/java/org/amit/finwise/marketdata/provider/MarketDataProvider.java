package org.amit.finwise.marketdata.provider;

public interface MarketDataProvider {
    String name();
    boolean supports(DataCapability capability);
    boolean isHealthy();
}
