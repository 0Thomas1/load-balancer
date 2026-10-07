package com.example.lb.scenario;

import com.example.lb.scenario.exceptions.ScenarioAlreadyActiveException;
import com.example.lb.scenario.exceptions.ScenarioNotFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry of the available scenarios plus the bookkeeping for which ones are
 * currently applied.
 *
 * <p>The engine deliberately does no I/O of its own: {@link Scenario#apply()}
 * and {@link Scenario#revert()} mutate the pool, and the caller reads the state
 * back off the pool. Nothing here emits events, so the dashboard's event stream
 * stays purely a record of request traffic.
 *
 * <p>Every operation is synchronized on the engine. REST threads drive the
 * scenarios, and the check-then-act sequence in {@link #start(String, Map)} —
 * "not already active", then apply, then mark active — has to be atomic or two
 * concurrent clicks would apply the same scenario twice and only the second
 * {@code revert()} would take effect.
 */
public class ScenarioEngine {

    /** Immutable once built, so a {@link ConcurrentHashMap} would only add noise. */
    private final Map<String, Scenario> registry;

    /** Insertion-ordered so {@link #stopAll()} reverts in reverse order of application. */
    private final Set<String> active = new LinkedHashSet<>();

    public ScenarioEngine(List<Scenario> scenarios) {
        Objects.requireNonNull(scenarios, "scenarios must not be null");
        Map<String, Scenario> built = new TreeMap<>();
        for (Scenario scenario : scenarios) {
            Objects.requireNonNull(scenario, "scenario must not be null");
            if (built.putIfAbsent(scenario.name(), scenario) != null) {
                throw new IllegalArgumentException("duplicate scenario name: " + scenario.name());
            }
        }
        this.registry = Collections.unmodifiableMap(built);
    }

    /**
     * Applies the named scenario with the given parameters, which may be
     * {@code null} or empty to accept the scenario's defaults.
     *
     * <p>If {@link Scenario#apply(Map)} throws, the scenario is left unapplied
     * rather than marked active, so a failed start cannot be "stopped" into a
     * revert of a state that never existed.
     *
     * @throws ScenarioNotFoundException   when no scenario is registered under that name
     * @throws ScenarioAlreadyActiveException when the scenario is already applied
     */
    public synchronized void start(String name, Map<String, String> params) {
        Scenario scenario = require(name);
        if (active.contains(name)) {
            throw new ScenarioAlreadyActiveException("scenario is already active: " + name);
        }
        scenario.apply(params);
        active.add(name);
    }

    /**
     * Reverts the named scenario and clears its active flag. Reverting a
     * scenario that is not active is a no-op, so a double stop is harmless.
     *
     * @throws ScenarioNotFoundException when no scenario is registered under that name
     */
    public synchronized void stop(String name) {
        Scenario scenario = require(name);
        active.remove(name);
        scenario.revert();
    }

    /**
     * Reverts every active scenario in reverse order of application, so a
     * scenario layered on top of another is undone first. Scenarios that throw
     * while reverting do not prevent the rest from being reverted.
     *
     * @return the number of scenarios that were active
     */
    public synchronized int stopAll() {
        List<String> running = new ArrayList<>(active);
        active.clear();
        int reverted = 0;
        for (int i = running.size() - 1; i >= 0; i--) {
            String name = running.get(i);
            try {
                registry.get(name).revert();
                reverted++;
            } catch (RuntimeException ignored) {
                // One misbehaving scenario must not strand the others in an
                // applied state with no active flag left to stop them.
            }
        }
        return reverted;
    }

    /**
     * @throws ScenarioNotFoundException when no scenario is registered under that name
     */
    public synchronized boolean isActive(String name) {
        require(name);
        return active.contains(name);
    }

    /** The registry, sorted by name, with each entry's current active flag. */
    public synchronized List<ScenarioInfo> list() {
        return registry.values().stream()
                .map(scenario -> new ScenarioInfo(
                        scenario.name(), scenario.description(), active.contains(scenario.name())))
                .toList();
    }

    private Scenario require(String name) {
        Scenario scenario = registry.get(name);
        if (scenario == null) {
            throw new ScenarioNotFoundException("no scenario registered with name: " + name);
        }
        return scenario;
    }
}