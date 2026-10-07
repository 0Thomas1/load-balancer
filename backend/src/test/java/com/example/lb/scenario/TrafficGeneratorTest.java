package com.example.lb.scenario;

import com.example.lb.algorithm.AlgorithmStrategy;
import com.example.lb.algorithm.ConsistentHashStrategy;
import com.example.lb.algorithm.IPHashStrategy;
import com.example.lb.algorithm.LeastConnectionsStrategy;
import com.example.lb.algorithm.RandomStrategy;
import com.example.lb.algorithm.RoundRobinStrategy;
import com.example.lb.algorithm.WeightedRoundRobinStrategy;
import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.metrics.MetricsCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TrafficGeneratorTest {

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RESET_TIMEOUT_MS = 60_000L;

    /**
     * How close to the target the eased rate has to get. The ease is exponential
     * with a 5 s time constant, so the rate approaches but never reaches the
     * target; 5 rps is tight enough to prove convergence and loose enough never
     * to flake, because the tests drive {@code tick()} directly instead of
     * waiting on a clock.
     */
    private static final double CONVERGENCE_TOLERANCE = 5.0;

    private BackendPool pool;
    private MetricsCollector metrics;
    private FakeClock clock;
    private TrafficGenerator generator;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        metrics = new MetricsCollector();
        LoadBalancer loadBalancer = new LoadBalancer(
                pool, algorithms(), new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS), metrics);
        loadBalancer.setAlgorithm(RoundRobinStrategy.NAME);
        clock = new FakeClock();
        // A null scheduler: the tests drive tick() directly, so start() is unused.
        generator = new TrafficGenerator(loadBalancer, null, clock);

        // Zero latency and zero failure rate, so a request never fails for a
        // reason of the generator's own making.
        pool.add(backend("b1"));
        pool.add(backend("b2"));
    }

    @Test
    void rejectsNonPositiveTargetRate() {
        assertThrows(IllegalArgumentException.class, () -> generator.setTargetRate(-1));
        assertThrows(IllegalArgumentException.class, () -> generator.setTargetRate(0));
    }

    @Test
    void emitsTheConfiguredRate() {
        AtomicInteger events = new AtomicInteger();
        generator.addListener(event -> events.incrementAndGet());
        generator.setTargetRate(10);

        for (int i = 0; i < 10; i++) {
            generator.tick();
        }

        // floor(10/10) == 1 request per tick, and never fewer than one.
        assertEquals(10, events.get(), "ten ticks at 10 rps emit ten requests");
        assertEquals(10L, metrics.totalRequests(), "the balancer saw every emitted request");
    }

    @Test
    void rampsRatherThanJumps() {
        generator.setTargetRate(100);

        generator.tick();
        assertTrue(generator.currentRate() <= 30,
                "the first tick barely moves, but was " + generator.currentRate());

        converge(generator, 100);

        assertTrue(Math.abs(generator.currentRate() - 100) <= CONVERGENCE_TOLERANCE,
                "the rate converged, but was " + generator.currentRate());
        assertTrue(generator.currentRate() <= 100, "the rate never overshoots the target");
    }

    @Test
    void burstsThenReturnsToTheBaseRate() {
        generator.setTargetRate(50);
        converge(generator, 50);

        generator.setBurst(200, 1_000L);
        assertTrue(generator.isBursting(), "the burst is active");
        assertEquals(200L, generator.targetRate(), "the burst rate is the effective target");

        converge(generator, 200);
        assertTrue(Math.abs(generator.currentRate() - 200) <= CONVERGENCE_TOLERANCE,
                "the burst rate was approached, but was " + generator.currentRate());

        clock.advance(1_000L);
        generator.tick();

        assertFalse(generator.isBursting(), "the expired burst ends on the next tick");
        assertEquals(50L, generator.targetRate(), "the base rate is the effective target again");
        converge(generator, 50);
        assertTrue(Math.abs(generator.currentRate() - 50) <= CONVERGENCE_TOLERANCE,
                "the rate eased back down, but was " + generator.currentRate());
    }

    @Test
    void notifiesListenersWithTheRoutedOutcome() {
        List<RequestEvent> events = new CopyOnWriteArrayList<>();
        generator.addListener(events::add);
        generator.setTargetRate(20);

        for (int i = 0; i < 50; i++) {
            generator.tick();
        }

        assertFalse(events.isEmpty(), "sanity: ticks produced events");
        List<String> knownBackendIds = pool.all().stream().map(Backend::id).toList();
        for (RequestEvent event : events) {
            assertNotNull(event.sourceIp(), "every event carries a source IP");
            assertTrue(event.sourceIp().matches("10\\.0\\.0\\.\\d{1,3}"),
                    "unexpected source IP " + event.sourceIp());
            assertTrue(knownBackendIds.contains(event.backendId()),
                    "unexpected backend id " + event.backendId());
        }
        assertTrue(events.stream().anyMatch(RequestEvent::success), "sanity: something succeeded");
    }

    /** Ticks until the eased rate is within {@link #CONVERGENCE_TOLERANCE} of {@code target}. */
    private void converge(TrafficGenerator generator, long target) {
        for (int i = 0; i < 500; i++) {
            generator.tick();
            if (Math.abs(generator.currentRate() - target) <= CONVERGENCE_TOLERANCE) {
                return;
            }
        }
        fail("the rate never got within " + CONVERGENCE_TOLERANCE + " of " + target);
    }

    private static Backend backend(String id) {
        return new Backend(id, id, new BackendConfig(0, 0.0, 100, 1));
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

    /** Hand-advanced clock, so burst expiry is exercised without sleeping. */
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