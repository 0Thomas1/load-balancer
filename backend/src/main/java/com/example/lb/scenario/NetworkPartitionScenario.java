package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Isolates a number of healthy backends by marking them {@code DRAINING}, the way
 * a network partition would take a slice of the cluster out of rotation.
 *
 * <p>Draining is used rather than down because the backends still answer when
 * addressed directly — they have simply stopped receiving new traffic, which is
 * what the balancer should be observed doing. The victims are sampled without
 * replacement from the currently healthy backends, so {@code count=2} always
 * takes two <em>distinct</em> backends, and a count larger than the healthy pool
 * simply takes all of them. Backends that are already draining are not eligible
 * and are never re-marked. {@link #revert()} brings back exactly the backends
 * this scenario drained, so an operator draining a node by hand is not undone.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code count} — how many backends to isolate, default
 *       {@value #DEFAULT_COUNT}. An unparsable value falls back to the default.</li>
 * </ul>
 */
public class NetworkPartitionScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "network-partition";

    /** How many backends are isolated when the caller passes no count. */
    static final int DEFAULT_COUNT = 1;

    private final BackendPool pool;
    private final Random random;

    /** Ids this scenario drained, so revert restores exactly those. */
    private volatile Set<String> partitioned = Set.of();

    public NetworkPartitionScenario(BackendPool pool) {
        this(pool, new Random());
    }

    /** Constructor with an injectable {@link Random}, for deterministic tests. */
    NetworkPartitionScenario(BackendPool pool, Random random) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Isolates healthy backends by draining them, simulating a network partition.";
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
            partitioned = Set.of();
            return;
        }
        List<Backend> victims = new ArrayList<>(healthy);
        Collections.shuffle(victims, random);
        Set<String> ids = new LinkedHashSet<>(count);
        for (Backend victim : victims.subList(0, count)) {
            victim.setStatus(BackendStatus.DRAINING);
            ids.add(victim.id());
        }
        partitioned = ids;
    }

    @Override
    public void revert() {
        Set<String> ids = partitioned;
        partitioned = Set.of();
        for (String id : ids) {
            pool.get(id).ifPresent(backend -> backend.setStatus(BackendStatus.UP));
        }
    }

    /**
     * Reads the {@code count} parameter, clamped to the healthy pool size.
     * Anything unparsable falls back to {@link #DEFAULT_COUNT}; the clamp to
     * {@code 0} in {@link #apply(Map)} is what makes a negative count a no-op
     * rather than an error.
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