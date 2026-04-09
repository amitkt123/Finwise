package org.amit.finwise.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads .env into the Spring Environment BEFORE any @ConfigurationProperties or
 * @Value binding happens. This ensures ${ENV_VAR} placeholders in application.properties
 * resolve correctly.
 *
 * Priority: lower than profile-specific properties (application-dev.properties)
 * so dev hardcoded values always win over .env values.
 */
public class DotenvConfig implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        Map<String, Object> props = new LinkedHashMap<>();
        dotenv.entries().forEach(e -> props.put(e.getKey(), e.getValue()));

        // addLast = lowest priority, so profile properties (application-dev.properties) always win
        environment.getPropertySources().addLast(new MapPropertySource("dotenv", props));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
