package com.example.lb.balancer;

import com.example.lb.algorithm.AlgorithmStrategy;
import com.example.lb.algorithm.exceptions.UnknownAlgorithmException;
import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.FailureReason;
import com.example.lb.backend.SimulationResult;
import com.example.lb.metrics.MetricsCollector;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Routes one request at a time to a backend, applying the configured algorithm
 * and the per-backend circuit breaker.
 *
 * <p>A {@link #route(String)} call is a complete, self-contained accounting
 * step: it picks a candidate, simulates the request, reports the outcome to the
 * circuit breaker, to the backend's own counters and to the balancer-wide
 * {@link MetricsCollector}, so that both the per-backend dashboard rows and the
 * global counters stay consistent without anyone else having to remember.
 *
 * <p>Instances are shared by all traffic threads: the algorithm is swapped
 * through an {@link AtomicReference} (a half-configured strategy is never
 * observable) and everything mutable lives in the injected collaborators.
 */
public class LoadBalancer {

    private final BackendPool pool;
    private final CircuitBreaker breaker;
    private final MetricsCollector metrics;
    private final Map<String, AlgorithmStrategy> algorithms;
    private final AtomicReference<AlgorithmStrategy> activeAlgorithm;

    /**
     * @param algorithms the selectable strategies, keyed by
     *                   {@link AlgorithmStrategy#getName()}; must not be empty.
     *                   The initially active one is the first name in sorted
     *                   order, which makes the default deterministic.
     */
    public LoadBalancer(BackendPool pool, Map<String, AlgorithmStrategy> algorithms,
                        CircuitBreaker breaker, MetricsCollector metrics) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.algorithms = Map.copyOf(Objects.requireNonNull(algorithms, "algorithms must not be null"));
        this.breaker = Objects.requireNonNull(breaker, "breaker must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        if (this.algorithms.isEmpty()) {
            throw new IllegalArgumentException("at least one algorithm must be registered");
        }
        String initial = this.algorithms.keySet().stream().sorted().findFirst().orElseThrow();
        this.activeAlgorithm = new AtomicReference<>(this.algorithms.get(initial));
    }

    /**
     * Routes one request from {@code sourceIp}.
     *
     * <p>Backends that are not {@code UP}, or whose circuit is {@code OPEN}, are
     * not candidates. When a backend's circuit has just come out of
     * {@code OPEN} it becomes selectable again so the breaker gets probed; if
     * that probe is already in flight the attempt is rejected rather than
     * piling extra load onto a backend that is still failing.
     *
     * @return the routing result; never {@code null} and never throws for a
     *         merely unroutable attempt ({@link FailureReason#NO_BACKENDS},
     *         {@link FailureReason#CIRCUIT_OPEN})
     */
    public RoutingResult route(String sourceIp) {
        List<Backend> candidates = pool.healthy();
        if (candidates.isEmpty()) {
            return recordUnroutable(FailureReason.NO_BACKENDS);
        }

        List<Backend> reachable = candidates.stream()
                .filter(backend -> !breaker.isOpen(backend.id()))
                .toList();
        if (reachable.isEmpty()) {
            return recordUnroutable(FailureReason.CIRCUIT_OPEN);
        }

        Backend selected = activeAlgorithm.get().select(reachable, sourceIp);
        if (!breaker.allowRequest(selected.id())) {
            return recordUnroutable(FailureReason.CIRCUIT_OPEN);
        }

        SimulationResult simulation;
        selected.stats().incrementActive();
        try {
            simulation = selected.simulate();
        } finally {
            selected.stats().decrementActive();
        }

        report(selected, simulation);
        return new RoutingResult(
                selected.id(),
                simulation.success(),
                simulation.latencyNanos(),
                simulation.reason());
    }

    /** Feeds the outcome back into the breaker and both levels of statistics. */
    private void report(Backend selected, SimulationResult simulation) {
        if (simulation.success()) {
            breaker.onSuccess(selected.id());
            selected.stats().recordSuccess(simulation.latencyNanos());
        } else {
            breaker.onFailure(selected.id());
            selected.stats().recordFailure(simulation.latencyNanos());
        }
        // The breaker just flipped (or kept) this backend's state; republish it
        // so BackendView reflects the circuit without a second source of truth.
        selected.stats().setCircuitState(breaker.state(selected.id()));
        metrics.record(simulation.success());
    }

    /** Counts an attempt that never reached a backend, but never a backend's stats. */
    private RoutingResult recordUnroutable(FailureReason reason) {
        metrics.record(false);
        return RoutingResult.unroutable(reason);
    }

    /** Name of the algorithm currently in use. */
    public String getAlgorithmName() {
        return activeAlgorithm.get().getName();
    }

    /**
     * Swaps the active algorithm.
     *
     * @throws UnknownAlgorithmException when no strategy is registered under that name
     */
    public void setAlgorithm(String name) {
        Objects.requireNonNull(name, "name must not be null");
        AlgorithmStrategy strategy = algorithms.get(name);
        if (strategy == null) {
            throw new UnknownAlgorithmException(name);
        }
        activeAlgorithm.set(strategy);
    }

    /** Names of every registered algorithm, sorted. */
    public List<String> availableAlgorithms() {
        return algorithms.keySet().stream().sorted().toList();
    }
}