package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one healthy backend into a slow, low-capacity node, so the dashboard can
 * show how much traffic the balancer pushes onto a backend that answers late.
 *
 * <p>One backend is enough to make the effect visible: the balancer keeps routing
 * to it at its configured share, the latency figures climb, and health checks
 * have something to react to. With nothing healthy there is nothing to pick and
 * {@link #apply()} is a no-op rather than an error.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code id} — the backend to slow, defaulting to the first healthy one
 *       in insertion order.</li>
 * </ul>
 */
public class SlowBackendScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "slow-backend";

    /** Latency, in milliseconds, the target backend is slowed to. */
    static final long SLOW_LATENCY_MS = 500L;

    /** Concurrency limit the target backend is throttled to. */
    static final int SLOW_CAPACITY = 1;

    private final BackendPool pool;

    /** The backend this scenario touched and its config from before the change. */
    private volatile Slowed slowed;

    public SlowBackendScenario(BackendPool pool) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Makes one healthy backend slow and capacity-constrained.";
    }

    @Override
    public void apply() {
        apply(Map.of());
    }

    @Override
    public void apply(Map<String, String> params) {
        Backend target = target(params);
        if (target == null) {
            return;
        }
        slowed = new Slowed(target.id(), target.config());
        target.updateConfig(target.config().copyWith(SLOW_LATENCY_MS, null, SLOW_CAPACITY, null));
    }

    @Override
    public void revert() {
        Slowed previous = slowed;
        slowed = null;
        if (previous == null) {
            return;
        }
        pool.get(previous.id()).ifPresent(backend -> backend.updateConfig(previous.config()));
    }

    /**
     * The backend to slow: the one named by {@code id}, or the first healthy
     * backend. Returns {@code null} when nothing is healthy and no id was given,
     * which {@link #apply()} treats as a no-op.
     *
     * @throws IllegalArgumentException when {@code id} names no registered backend
     */
    private Backend target(Map<String, String> params) {
        String id = params == null ? null : params.get("id");
        if (id == null || id.isBlank()) {
            List<Backend> healthy = pool.healthy();
            return healthy.isEmpty() ? null : healthy.get(0);
        }
        return pool.get(id.trim())
                .orElseThrow(() -> new IllegalArgumentException("no backend registered with id: " + id));
    }

    /** A backend id plus the config it had before this scenario changed it. */
    private record Slowed(String id, BackendConfig config) {
    }
}