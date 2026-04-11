package org.amit.finwise.policy.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "policy")
public class PolicySourceProperties {

    private int maxArticlesPerSource = 10;
    private int fetchTimeoutMs = 15000;
    private String userAgent = "FinwisePolicyBot/1.0";
    private String storageDir = "/tmp/finwise-policy";
    private boolean htmlExtractionEnabled = true;
    private boolean pdfExtractionEnabled = true;
    private Map<String, SourceConfig> sources = new LinkedHashMap<>();

    @Getter
    @Setter
    public static class SourceConfig {
        private boolean enabled = true;
        private String feedUrl;
        private String baseUrl;
        private String authority;
        private String documentType;
        private String bindingLevel;
        private String policyArea;
    }
}
