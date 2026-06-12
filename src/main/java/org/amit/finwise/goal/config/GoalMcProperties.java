package org.amit.finwise.goal.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Monte-Carlo goal-engine configuration (prefix {@code cfo.goal}).
 *
 * The simulator is deterministic given {@code seed}: the same seed reproduces the
 * same paths, and — crucially — the required-SIP bisection re-runs the simulation
 * under one fixed seed so that success probability is monotone in the contribution.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "cfo.goal")
public class GoalMcProperties {

    /** Simulation engine: {@code gbm} (lognormal) or {@code bootstrap} (resample history). */
    private String mcMode = "gbm";

    /** Number of simulated paths. */
    private int paths = 10_000;

    /** RNG seed — fixed for reproducibility; override per environment if desired. */
    private long seed = 20260612L;

    /** Planning drift (fraction p.a.) when neither portfolio history nor the goal sets one. */
    private double defaultDrift = 0.10;

    /** Planning volatility (fraction p.a.) used when portfolio history is too short. */
    private double defaultVol = 0.18;

    /** Minimum monthly observations required before bootstrap mode is used. */
    private int bootstrapMinMonths = 36;
}
