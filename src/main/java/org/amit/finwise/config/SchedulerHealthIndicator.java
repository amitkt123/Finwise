package org.amit.finwise.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component("schedulerHealth")
public class SchedulerHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final TaskScheduler taskScheduler;

    public SchedulerHealthIndicator(JdbcTemplate jdbcTemplate, TaskScheduler taskScheduler) {
        this.jdbcTemplate = jdbcTemplate;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public Health health() {
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (result == null || result != 1) {
                return Health.down().withDetail("db", "ping failed").build();
            }

            boolean schedulerRunning = taskScheduler != null;

            return Health.up()
                    .withDetail("db", "UP")
                    .withDetail("scheduler", schedulerRunning ? "UP" : "UNKNOWN")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("db", "unreachable").build();
        }
    }
}
