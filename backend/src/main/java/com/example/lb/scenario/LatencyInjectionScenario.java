package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adds a fixed amount of latency to every healthy backend, so the dashboard can
 * show what the balancer's latency figures and health checks do when responses
 * get slower.
 *
 * <p>Latency is added to the backend's <em>configured</em> latency rather than
 * replaced, so a backend that was already slow gets slower by the same amount.
 * The whole config each backend had before the injection is remembered per
 * backend id and written back by {@link #revert()}, which leaves every field
 * that was not touched exactly as it was. With nothing healthy there is nothing
 * to slow down, and {@link #apply()} is a no-op rather than an error.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code inject} — milliseconds to add, default {@value #DEFAULT_INJECT_MS}.
 *       An unparsable value falls back to the default, so a hand-typed REST
 *       request still produces a visible effect.</li>
 * </ul>
 */
public class LatencyInjectionScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "latency-injection";

    /** Milliseconds added to each backend when the caller passes no {@code inject}. */
    static final long DEFAULT_INJECT_MS = 100L;

    private final BackendPool pool;
    private final long defaultInjectMs;

    /**
     * Config of each backend as it was when this scenario first touched it.
     * Backends already recorded are kept, so applying twice and reverting once
     * still lands on the original config.
     */
    private volatile Map<String, BackendConfig> originals = Map.of();

    public LatencyInjectionScenario(BackendPool pool) {
        this(pool, DEFAULT_INJECT_MS);
    }

    /** Constructor with an explicit default, so callers can pick their own injection. */
    public LatencyInjectionScenario(BackendPool pool, long defaultInjectMs) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        if (defaultInjectMs < 0) {
            throw new IllegalArgumentException("defaultInjectMs must be >= 0, but was " + defaultInjectMs);
        }
        this.defaultInjectMs = defaultInjectMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Adds latency to every healthy backend to show the effect of slow responses.";
    }

    @Override
    public void apply() {
        apply(Map.of());
    }

    @Override
    public void apply(Map<String, String> params) {
        long injectMs = requestedInjectMs(params);
        Map<String, BackendConfig> snapshot = new HashMap<>(originals);
        for (Backend backend : pool.healthy()) {
            snapshot.putIfAbsent(backend.id(), backend.config());
            backend.updateConfig(backend.config().copyWith(
                    backend.config().latencyMs() + injectMs, null, null, null));
        }
        originals = snapshot;
    }

    @Override
    public void revert() {
        Map<String, BackendConfig> snapshot = originals;
        originals = Map.of();
        snapshot.forEach((id, config) -> pool.get(id).ifPresent(backend -> backend.updateConfig(config)));
    }

    /**
     * Reads the {@code inject} parameter, falling back to {@link #defaultInjectMs}
     * when it is absent, unparsable or negative — a zero or negative injection
     * would be indistinguishable from no-op anyway.
     */
    private long requestedInjectMs(Map<String, String> params) {
        String raw = params == null ? null : params.get("inject");
        if (raw == null) {
            return defaultInjectMs;
        }
        try {
            return Math.max(0, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return defaultInjectMs;
        }
    }
}