package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import static com.example.lb.backend.BackendStatus.DOWN;
import static com.example.lb.backend.BackendStatus.UP;

/**
 * Takes a number of healthy backends out of rotation, so the dashboard can be
 * watched while the balancer copes (or fails to) with the missing capacity.
 *
 * <p>The victims are sampled without replacement from the currently healthy
 * backends: {@code count=2} always takes two <em>distinct</em> backends down.
 * A count larger than the healthy pool simply takes all of them. Backends that
 * were already down, draining or down for another reason are never touched,
 * and {@link #revert()} restores exactly the backends this scenario took down —
 * nothing else, so a concurrent outage is not silently undone.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code count} — how many backends to take down, default
 *       {@value #DEFAULT_COUNT}. An unparsable value falls back to the default
 *       rather than failing the whole scenario, so a hand-typed REST request
 *       still produces a visible effect.</li>
 * </ul>
 */
public class ConsumerDropScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "consumer-drop";

    /** How many backends are dropped when the caller passes no count. */
    static final int DEFAULT_COUNT = 1;

    private final BackendPool pool;
    private final Random random;

    /** Ids this scenario took down, so revert restores exactly those. */
    private volatile Set<String> dropped = Set.of();

    public ConsumerDropScenario(BackendPool pool, Random random) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Takes healthy backends out of rotation (count) to show the balancer under consumer drop.";
    }

    @Override
    public void apply() {
        apply(Map.of());
    }

    @Override
    public void apply(Map<String, String> params) {
        List<Backend> healthy = pool.healthy();
        int count = Math.min(requestedCount(params), healthy.size());
        if (count <= 0) {
            dropped = Set.of();
            return;
        }
        List<Backend> victims = new ArrayList<>(healthy);
        Collections.shuffle(victims, random);
        Set<String> ids = new LinkedHashSet<>(count);
        for (Backend victim : victims.subList(0, count)) {
            victim.setStatus(DOWN);
            ids.add(victim.id());
        }
        dropped = ids;
    }

    @Override
    public void revert() {
        Set<String> ids = dropped;
        dropped = Set.of();
        for (String id : ids) {
            pool.get(id).ifPresent(backend -> backend.setStatus(UP));
        }
    }

    /**
     * Reads the {@code count} parameter, clamped to the healthy pool size.
     * Anything unparsable or negative falls back to {@link #DEFAULT_COUNT}; the
     * clamp to {@code 0} in {@link #apply(Map)} is what makes a negative count a
     * no-op rather than an error.
     */
    private int requestedCount(Map<String, String> params) {
        String raw = params == null ? null : params.get("count");
        if (raw == null) {
            return DEFAULT_COUNT;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }
}