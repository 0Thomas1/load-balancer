package com.example.lb.balancer;

import com.example.lb.algorithm.AlgorithmStrategy;
import com.example.lb.algorithm.ConsistentHashStrategy;
import com.example.lb.algorithm.IPHashStrategy;
import com.example.lb.algorithm.LeastConnectionsStrategy;
import com.example.lb.algorithm.RandomStrategy;
import com.example.lb.algorithm.RoundRobinStrategy;
import com.example.lb.algorithm.WeightedRoundRobinStrategy;
import com.example.lb.algorithm.exceptions.UnknownAlgorithmException;
import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.backend.CircuitState;
import com.example.lb.backend.FailureReason;
import com.example.lb.metrics.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalancerTest {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RESET_TIMEOUT_MS = 60_000L;

    private BackendPool pool;
    private MetricsCollector metrics;
    private CircuitBreaker breaker;
    private LoadBalancer loadBalancer;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        metrics = new MetricsCollector();
        breaker = new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS);
        loadBalancer = new LoadBalancer(pool, algorithms(), breaker, metrics);
        loadBalancer.setAlgorithm(RoundRobinStrategy.NAME);

        pool.add(backend("b1"));
        pool.add(backend("b2"));
    }

    @Test
    void roundRobinAlternatesBetweenBackends() {
        List<String> routed = List.of(
                loadBalancer.route("10.0.0.1").backendId(),
                loadBalancer.route("10.0.0.1").backendId(),
                loadBalancer.route("10.0.0.1").backendId(),
                loadBalancer.route("10.0.0.1").backendId());

        assertEquals(List.of("b1", "b2", "b1", "b2"), routed);
    }

    @Test
    void skipsDownBackends() {
        pool.get("b2").orElseThrow().setStatus(BackendStatus.DOWN);

        assertEquals(List.of("b1", "b1", "b1"), routeThree());
    }

    @Test
    void skipsDrainingBackends() {
        pool.get("b2").orElseThrow().setStatus(BackendStatus.DRAINING);

        assertEquals(List.of("b1", "b1", "b1"), routeThree());
    }

    @Test
    void allDownReportsNoBackends() {
        pool.all().forEach(backend -> backend.setStatus(BackendStatus.DOWN));

        RoutingResult result = loadBalancer.route("10.0.0.1");

        assertNull(result.backendId());
        assertFalse(result.success());
        assertEquals(0L, result.latencyNanos());
        assertEquals(FailureReason.NO_BACKENDS, result.reason());
        // An unroutable attempt still counts as an arrival for the dashboard.
        assertEquals(1L, metrics.totalRequests());
        assertEquals(1L, metrics.errorCount());
    }

    @Test
    void openCircuitSkipsTheBackend() {
        openCircuit("b1");

        assertEquals(List.of("b2", "b2", "b2"), routeThree());
    }

    @Test
    void allCircuitsOpenReportsCircuitOpen() {
        openCircuit("b1");
        openCircuit("b2");

        RoutingResult result = loadBalancer.route("10.0.0.1");

        assertNull(result.backendId());
        assertFalse(result.success());
        assertEquals(0L, result.latencyNanos());
        assertEquals(FailureReason.CIRCUIT_OPEN, result.reason());
        assertEquals(1L, metrics.errorCount());
    }

    @Test
    void recordsStatsOnBackendAndInMetrics() {
        RoutingResult success = loadBalancer.route("10.0.0.1");
        RoutingResult failure = loadBalancer.route("10.0.0.2");

        assertTrue(success.success());
        assertEquals(FailureReason.NONE, success.reason());

        Backend b1 = pool.get("b1").orElseThrow();
        Backend b2 = pool.get("b2").orElseThrow();

        assertEquals(1L, b1.stats().totalRequests());
        assertEquals(0L, b1.stats().errorCount());
        assertEquals(1L, b2.stats().totalRequests());
        assertEquals(0L, b2.stats().errorCount());

        assertEquals(2L, metrics.totalRequests());
        assertEquals(0L, metrics.errorCount());

        // No connection leaked by either routing.
        assertEquals(0, b1.stats().activeConnections());
        assertEquals(0, b2.stats().activeConnections());
    }

    @Test
    void recordsFailureOnBackendAndInMetrics() {
        pool.get("b1").orElseThrow().updateConfig(backendConfig(1, 1.0));

        RoutingResult result = loadBalancer.route("10.0.0.1");

        assertFalse(result.success());
        assertEquals(FailureReason.BACKEND_FAILED, result.reason());

        Backend b1 = pool.get("b1").orElseThrow();
        assertEquals(1L, b1.stats().totalRequests());
        assertEquals(1L, b1.stats().errorCount());
        assertEquals(0, b1.stats().activeConnections());

        assertEquals(1L, metrics.totalRequests());
        assertEquals(1L, metrics.errorCount());
    }

    @Test
    void switchingAlgorithmTakesEffect() {
        assertEquals(RoundRobinStrategy.NAME, loadBalancer.getAlgorithmName());
        assertEquals("b1", loadBalancer.route("10.0.0.1").backendId());

        Backend b1 = pool.get("b1").orElseThrow();
        b1.stats().incrementActive();
        b1.stats().incrementActive();
        assertEquals(0, pool.get("b2").orElseThrow().stats().activeConnections());

        loadBalancer.setAlgorithm(LeastConnectionsStrategy.NAME);

        assertEquals(LeastConnectionsStrategy.NAME, loadBalancer.getAlgorithmName());
        assertEquals("b2", loadBalancer.route("10.0.0.1").backendId());
    }

    @Test
    void unknownAlgorithmIsRejectedAndKeepsThePreviousOne() {
        assertThrows(UnknownAlgorithmException.class, () -> loadBalancer.setAlgorithm("nope"));

        assertEquals(RoundRobinStrategy.NAME, loadBalancer.getAlgorithmName());
    }

    @Test
    void availableAlgorithmsAreTheSixRegisteredNames() {
        assertEquals(
                List.of("consistent-hash", "ip-hash", "least-connections", "random", "round-robin", "weighted-round-robin"),
                loadBalancer.availableAlgorithms());
    }

    @Test
    void capacityOverflowFailsFast() {
        pool.remove("b2");
        Backend b1 = pool.get("b1").orElseThrow();
        b1.updateConfig(backendConfig(1, 0.0, 1));
        b1.stats().incrementActive();

        RoutingResult result = loadBalancer.route("10.0.0.1");

        assertEquals("b1", result.backendId());
        assertFalse(result.success());
        assertEquals(0L, result.latencyNanos());
        assertEquals(FailureReason.CAPACITY_EXCEEDED, result.reason());
        // The attempt was rejected inside simulate(), but the connection the
        // balancer took for it is still released.
        assertEquals(1, b1.stats().activeConnections(), "the balancer released the connection it took");
        assertEquals(1L, metrics.errorCount());
    }

    @Test
    void halfOpenProbeFailureReopensTheCircuit() {
        FakeClock clock = new FakeClock();
        CircuitBreaker timedBreaker = new CircuitBreaker(FAILURE_THRESHOLD, 1_000L, clock);
        LoadBalancer probing = new LoadBalancer(pool, algorithms(), timedBreaker, metrics);
        // Least-connections always prefers the first candidate with the fewest
        // in-flight requests, so which backend is probed is deterministic.
        probing.setAlgorithm(LeastConnectionsStrategy.NAME);
        pool.get("b1").orElseThrow().updateConfig(backendConfig(1, 1.0));

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            timedBreaker.onFailure("b1");
        }
        assertTrue(timedBreaker.isOpen("b1"), "sanity: circuit of b1 is open");
        assertEquals("b2", probing.route("10.0.0.1").backendId(), "the open circuit keeps b1 out");

        clock.advance(1_000L);
        assertEquals(CircuitState.HALF_OPEN, timedBreaker.state("b1"), "sanity: reset timeout elapsed");

        RoutingResult probe = probing.route("10.0.0.1");

        assertEquals("b1", probe.backendId(), "the half-open backend becomes selectable again");
        assertFalse(probe.success());
        assertEquals(FailureReason.BACKEND_FAILED, probe.reason());
        assertTrue(timedBreaker.isOpen("b1"), "the failed probe re-opened the circuit");
        assertEquals(CircuitState.OPEN, pool.get("b1").orElseThrow().stats().circuitState(),
                "the dashboard sees the re-opened circuit");
    }

    @Test
    void halfOpenProbeSuccessClosesTheCircuit() {
        FakeClock clock = new FakeClock();
        CircuitBreaker timedBreaker = new CircuitBreaker(FAILURE_THRESHOLD, 1_000L, clock);
        LoadBalancer probing = new LoadBalancer(pool, algorithms(), timedBreaker, metrics);
        probing.setAlgorithm(RoundRobinStrategy.NAME);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            timedBreaker.onFailure("b1");
        }
        clock.advance(1_000L);

        RoutingResult probe = probing.route("10.0.0.1");

        assertEquals("b1", probe.backendId());
        assertTrue(probe.success());
        assertFalse(timedBreaker.isOpen("b1"));
        assertEquals(CircuitState.CLOSED, pool.get("b1").orElseThrow().stats().circuitState());
    }

    @Test
    void halfOpenProbeAlreadyInFlightIsNotJoined() {
        FakeClock clock = new FakeClock();
        CircuitBreaker timedBreaker = new CircuitBreaker(FAILURE_THRESHOLD, 1_000L, clock);
        LoadBalancer probing = new LoadBalancer(pool, algorithms(), timedBreaker, metrics);
        probing.setAlgorithm(LeastConnectionsStrategy.NAME);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            timedBreaker.onFailure("b1");
        }
        clock.advance(1_000L);
        // Stand in for a concurrent request that already took the half-open probe.
        assertTrue(timedBreaker.allowRequest("b1"), "sanity: the probe is taken");

        RoutingResult result = probing.route("10.0.0.1");

        assertNull(result.backendId());
        assertFalse(result.success());
        assertEquals(0L, result.latencyNanos());
        assertEquals(FailureReason.CIRCUIT_OPEN, result.reason(),
                "the in-flight probe owns the half-open slot; no second request is sent");
        assertEquals(0L, pool.get("b1").orElseThrow().stats().totalRequests(),
                "the rejected attempt never reached the backend");
        assertEquals(1L, metrics.errorCount());
    }

    @Test
    void openingTheCircuitIsPublishedToTheBackendStats() {
        CircuitBreaker tripwire = new CircuitBreaker(1, 60_000L);
        LoadBalancer trip = new LoadBalancer(pool, algorithms(), tripwire, metrics);
        trip.setAlgorithm(RoundRobinStrategy.NAME);
        pool.get("b1").orElseThrow().updateConfig(backendConfig(1, 1.0));

        trip.route("10.0.0.1");

        assertTrue(tripwire.isOpen("b1"));
        assertEquals(CircuitState.OPEN, pool.get("b1").orElseThrow().stats().circuitState(),
                "the dashboard sees the circuit that just tripped");
    }

    @Test
    void noSourceIpStillRoutes() {
        RoutingResult result = loadBalancer.route(null);

        assertEquals("b1", result.backendId());
        assertTrue(result.success());
    }

    private List<String> routeThree() {
        return List.of(
                loadBalancer.route("10.0.0.1").backendId(),
                loadBalancer.route("10.0.0.1").backendId(),
                loadBalancer.route("10.0.0.1").backendId());
    }

    private void openCircuit(String backendId) {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.onFailure(backendId);
        }
        assertTrue(breaker.isOpen(backendId), "sanity: circuit of " + backendId + " is open");
    }

    private static Backend backend(String id) {
        return new Backend(id, id, backendConfig(0, 0.0, 100));
    }

    private static BackendConfig backendConfig(long latencyMs, double failureRate) {
        return backendConfig(latencyMs, failureRate, 100);
    }

    private static BackendConfig backendConfig(long latencyMs, double failureRate, int capacity) {
        return new BackendConfig(latencyMs, failureRate, capacity, 1);
    }

    private static Map<String, AlgorithmStrategy> algorithms() {
        List<AlgorithmStrategy> strategies = List.of(
                new RoundRobinStrategy(),
                new WeightedRoundRobinStrategy(),
                new RandomStrategy(),
                new LeastConnectionsStrategy(),
                new IPHashStrategy(),
                new ConsistentHashStrategy());
        return strategies.stream()
                .collect(Collectors.toUnmodifiableMap(AlgorithmStrategy::getName, strategy -> strategy));
    }

    /** Hand-advanced clock so the circuit's timeout is exercised without sleeping. */
    private static final class FakeClock implements LongSupplier {

        private long nowMs = 1_000L;

        @Override
        public long getAsLong() {
            return nowMs;
        }

        void advance(long deltaMs) {
            nowMs += deltaMs;
        }
    }
}