package com.example.lb.scenario;

import java.util.Map;

/**
 * A named, reversible perturbation of the simulation: {@link #apply()} puts the
 * sandbox into an interesting state and {@link #revert()} puts it back.
 *
 * <p>{@link #name()} is the registry key the {@link ScenarioEngine} and the REST
 * layer address a scenario by, so it is pinned: it must not change once the UI
 * ships.
 *
 * <p>Implementations are expected to be safe to {@link #revert()} when they were
 * never applied — the engine may be stopped twice, or a scenario that turned
 * out to be a no-op may be stopped anyway.
 */
public interface Scenario {

    /** Stable registry key, e.g. {@code "consumer-drop"}. */
    String name();

    /** One-line human description, shown by the dashboard. */
    String description();

    /** Puts the sandbox into this scenario's state. */
    void apply();

    /**
     * Applies the scenario with the caller's parameters, for example
     * {@code count=2} for {@link ConsumerDropScenario}.
     *
     * <p>The default ignores the parameters and applies the scenario's own
     * defaults, so a scenario with nothing to configure needs no override.
     */
    default void apply(Map<String, String> params) {
        apply();
    }

    /** Restores whatever {@code apply()} changed. A no-op when never applied. */
    void revert();
}