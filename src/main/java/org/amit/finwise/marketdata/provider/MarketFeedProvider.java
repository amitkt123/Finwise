package org.amit.finwise.marketdata.provider;

public interface MarketFeedProvider {
    String name();
    boolean supports(DataCapability capability);
    boolean isHealthy();
}
