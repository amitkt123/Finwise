package org.amit.finwise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "finwise.rate-limit.llm")
public class RateLimitProperties {

    private int limitForPeriod = 10;
    private int limitRefreshPeriodSeconds = 60;
    private long timeoutMs = 0;

    public int getLimitForPeriod() { return limitForPeriod; }
    public void setLimitForPeriod(int limitForPeriod) { this.limitForPeriod = limitForPeriod; }

    public int getLimitRefreshPeriodSeconds() { return limitRefreshPeriodSeconds; }
    public void setLimitRefreshPeriodSeconds(int s) { this.limitRefreshPeriodSeconds = s; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
