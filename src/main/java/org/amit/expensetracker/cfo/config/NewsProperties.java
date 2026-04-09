package org.amit.expensetracker.cfo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cfo.news")
public class NewsProperties {

    private Map<String, String> sources = new LinkedHashMap<>();
    private int maxArticlesPerSource = 15;
    private int fetchTimeoutMs = 10000;
}
