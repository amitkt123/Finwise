package org.amit.finwise.cfo.service.research;

/**
 * Adapter marker for stock research data feeds.
 *
 * Default v1 sources are free/public where available:
 * Yahoo chart API for adjusted prices, NSE corporate data subscription
 * (https://www.nseindia.com/market-data/corporate-data-subscription),
 * BSE corporates (https://www.bseindia.com/corporates.html), RBI DBIE
 * (https://dbieold.rbi.org.in/DBIE/), and MOSPI/NSO releases.
 *
 * Paid providers such as Stoxim or normalized estimates/news feeds can
 * implement this boundary without changing scorecard logic.
 */
public interface MarketDataProvider {
    String providerName();

    interface FundamentalsProvider extends MarketDataProvider {
        default boolean supportsNormalizedPeers() {
            return false;
        }

        default boolean supportsBankAssetQuality() {
            return false;
        }
    }

    interface MarketEventsProvider extends MarketDataProvider {}
}
