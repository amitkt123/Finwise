package org.amit.finwise.marketdata.provider.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.marketdata.provider.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BSEFilingsAdapter implements MarketFeedProvider {

    private final RestClient.Builder restClientBuilder;

    @Override public String name() { return "bse-filings"; }
    @Override public boolean supports(DataCapability c) { return c == DataCapability.CORPORATE_FILINGS; }
    @Override public boolean isHealthy() { return true; }

    /**
     * Fetches shareholding pattern for a BSE scrip code.
     * BSE API: https://www.bseindia.com/corporates/shpAPI.aspx?scripcode={code}&qtrid=1
     */
    public DataEnvelope<PromoterFilingSnapshot> fetchShareholdingPattern(String bseScripCode, String symbol) {
        try {
            Map<?, ?> response = restClientBuilder.build()
                .get()
                .uri("https://www.bseindia.com/corporates/shpAPI.aspx"
                    + "?scripcode=" + bseScripCode + "&qtrid=1")
                .header("User-Agent", "Mozilla/5.0")
                .retrieve()
                .body(Map.class);

            if (response == null) return DataEnvelope.missing(name(), "Empty response for " + bseScripCode);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("Table");
            if (data == null || data.isEmpty()) return DataEnvelope.missing(name(), "No data for " + bseScripCode);

            // Aggregate by category
            BigDecimal promoterPct = BigDecimal.ZERO, promoterPledgedPct = BigDecimal.ZERO;
            BigDecimal fiiPct = BigDecimal.ZERO, diiPct = BigDecimal.ZERO, retailPct = BigDecimal.ZERO;
            String quarterEnd = "";

            for (Map<String, Object> row : data) {
                String category = row.getOrDefault("Shareholder_Category", "").toString();
                BigDecimal pct = new BigDecimal(row.getOrDefault("Shareholding_Percentage", "0").toString());
                quarterEnd = row.getOrDefault("Quarter_Date", "").toString();
                if (category.contains("Promoter")) promoterPct = promoterPct.add(pct);
                else if (category.contains("FII") || category.contains("Foreign")) fiiPct = fiiPct.add(pct);
                else if (category.contains("DII") || category.contains("Mutual Fund")) diiPct = diiPct.add(pct);
                else if (category.contains("Public")) retailPct = retailPct.add(pct);
            }

            PromoterFilingSnapshot snap = new PromoterFilingSnapshot(
                symbol, quarterEnd, promoterPct, promoterPledgedPct, fiiPct, diiPct, retailPct);
            return DataEnvelope.of(snap, name(), DataQuality.EOD);

        } catch (Exception e) {
            log.error("[BSEFilings] fetch failed for {}: {}", bseScripCode, e.getMessage());
            return DataEnvelope.missing(name(), "BSE API error: " + e.getMessage());
        }
    }
}
