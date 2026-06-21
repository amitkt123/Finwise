package org.amit.finwise.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /** 5-minute TTL for user-scoped portfolio snapshots. */
    public static final String PORTFOLIO_SNAPSHOT = "portfolioSnapshot";

    /** 15-minute TTL for global news (no personalization, shared across users). */
    public static final String NEWS_CACHE = "newsCache";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(PORTFOLIO_SNAPSHOT,
                Caffeine.newBuilder().maximumSize(500).expireAfterWrite(5, TimeUnit.MINUTES).build());
        manager.registerCustomCache(NEWS_CACHE,
                Caffeine.newBuilder().maximumSize(100).expireAfterWrite(15, TimeUnit.MINUTES).build());
        return manager;
    }
}
