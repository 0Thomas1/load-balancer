package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.CircuitState;
import com.example.lb.balancer.CircuitBreaker;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Drives one healthy backend's failure rate to {@code 1.0} and opens its circuit,
 * so the dashboard can show the balancer pulling traffic away from a failing node.
 *
 * <p>The circuit is tripped by reporting failures directly rather than by sending
 * real traffic: the failure rate makes every request fail anyway, and calling
 * {@link CircuitBreaker#onFailure(String)} is deterministic, so the circuit is
 * open the moment {@link #apply()} returns instead of after five round trips.
 * Enough failures are reported to open the circuit whatever threshold the
 * breaker was configured with.
 *
 * <p>Only the target backend is touched, and its whole original config is put
 * back by {@link #revert()}, which also closes the circuit again — so the pool
 * and the breaker end up exactly as they started.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code id} — the backend to fail, defaulting to the first healthy one
 *       in insertion order.</li>
 * </ul>
 */
public class CircuitBreakerTripScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "circuit-breaker-trip";

    /** Failure rate the target backend is driven to: every request fails. */
    static final double FAILURE_RATE = 1.0;

    /**
     * Upper bound on the failures reported to open the circuit. The breaker
     * opens on its own threshold, so this is only a guard against a breaker
     * configured so high that the circuit could never open.
     */
    private static final int MAX_TRIP_ATTEMPTS = 1_000;

    private final BackendPool pool;
    private final CircuitBreaker breaker;

    /** The backend this scenario touched and its config from before the change. */
    private volatile Tripped tripped;

    public CircuitBreakerTripScenario(BackendPool pool, CircuitBreaker breaker) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Makes one healthy backend fail every request and trips its circuit breaker.";
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
        BackendConfig original = target.config();
        target.updateConfig(original.copyWith(null, FAILURE_RATE, null, null));
        if (!trip(target.id())) {
            // All or nothing: an unopenable circuit would otherwise strand the
            // backend at a 100% failure rate with nothing to revert it.
            target.updateConfig(original);
            throw new IllegalStateException(
                    "circuit for " + target.id() + " did not open within " + MAX_TRIP_ATTEMPTS + " failures");
        }
        tripped = new Tripped(target.id(), original);
    }

    @Override
    public void revert() {
        Tripped previous = tripped;
        tripped = null;
        if (previous == null) {
            return;
        }
        pool.get(previous.id()).ifPresent(backend -> backend.updateConfig(previous.config()));
        breaker.reset(previous.id());
    }

    /**
     * Reports failures until the breaker leaves the closed state, which happens
     * at whatever threshold it was configured with.
     *
     * <p>"Left closed" rather than "is open" is the condition to aim for: a
     * breaker with a zero reset timeout reports its own circuits as half-open
     * the moment they trip, and such a circuit is just as much out of rotation
     * as an open one.
     *
     * @return whether the circuit could be tripped at all
     */
    private boolean trip(String backendId) {
        for (int attempt = 0; attempt < MAX_TRIP_ATTEMPTS; attempt++) {
            if (breaker.state(backendId) != CircuitState.CLOSED) {
                return true;
            }
            breaker.onFailure(backendId);
        }
        return breaker.state(backendId) != CircuitState.CLOSED;
    }

    /**
     * The backend to fail: the one named by {@code id}, or the first healthy
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
    private record Tripped(String id, BackendConfig config) {
    }
}