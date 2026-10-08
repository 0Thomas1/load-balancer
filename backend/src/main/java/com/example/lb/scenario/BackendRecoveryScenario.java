package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.scenario.exceptions.ScenarioPreconditionException;

import java.util.List;
import java.util.Objects;
import java.util.Random;

import static com.example.lb.backend.BackendStatus.DOWN;
import static com.example.lb.backend.BackendStatus.UP;

/**
 * Brings one downed backend back into rotation and closes its circuit, so the
 * dashboard can show the balancer re-balancing as capacity returns.
 *
 * <p>Recovery is a one-way nudge rather than a reversible perturbation: the
 * backend is healthy again, which is exactly what {@link #apply()} does, so
 * there is nothing for {@link #revert()} to undo. Stopping this scenario is a
 * no-op; use it to drain the "active" flag without changing the pool.
 *
 * <p>A backend is only recovered when its status is {@code DOWN}: draining back
 * nodes are left alone, because draining is a deliberate operator action, not an
 * outage. With nothing down there is nothing to recover and {@link #apply()}
 * says so.
 */
public class BackendRecoveryScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "backend-recovery";

    private final BackendPool pool;
    private final CircuitBreaker breaker;
    private final Random random;

    public BackendRecoveryScenario(BackendPool pool, CircuitBreaker breaker) {
        this(pool, breaker, new Random());
    }

    /** Constructor with an injectable {@link Random}, for deterministic tests. */
    BackendRecoveryScenario(BackendPool pool, CircuitBreaker breaker, Random random) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Brings one DOWN backend back UP and closes its circuit.";
    }

    @Override
    public void apply() {
        List<Backend> downed = pool.all().stream()
                .filter(backend -> backend.status() == DOWN)
                .toList();
        if (downed.isEmpty()) {
            throw new ScenarioPreconditionException("no backend is DOWN");
        }
        Backend recovered = downed.get(random.nextInt(downed.size()));
        recovered.setStatus(UP);
        breaker.reset(recovered.id());
    }

    /** No-op: recovery moves the sandbox forward, there is nothing to roll back. */
    @Override
    public void revert() {
    }
}