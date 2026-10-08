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
import com.example.lb.backend.BackendStatus;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.balancer.LoadBalancer;
import com.example.lb.metrics.MetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both scenarios run against real collaborators: a real {@link TrafficGenerator}
 * on a real {@link ScheduledExecutorService}, and a real {@link BackendPool}.
 *
 * <p>Nothing is asserted on a bare sleep. Where the cascade is expected to have
 * progressed the test polls the pool for the condition with a generous timeout,
 * and where a cancelled cascade is expected to stay quiet it re-checks the pool
 * repeatedly over the window that a cancelled drop could still have fired in.
 */
class AsyncScenariosTest {

    /** Gap between cascade drops. Long enough to observe the steps, short enough to stay quick. */
    private static final long CASCADE_DELAY_MS = 50L;

    /** Upper bound for a cascade step to land; four times the delay, so a busy CI box still passes. */
    private static final long POLL_TIMEOUT_MS = 2_000L;

    /** How often the poll re-reads the pool. */
    private static final long POLL_INTERVAL_MS = 20L;

    /** Zero latency and no failures, so a tick never blocks or fails on its own account. */
    private static final BackendConfig CONFIG = new BackendConfig(0, 0.0, 100, 1);

    private ScheduledExecutorService scheduler;
    private BackendPool pool;
    private TrafficGenerator generator;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
        pool = new BackendPool();
        pool.add(backend("b1"));
        pool.add(backend("b2"));
        pool.add(backend("b3"));
        generator = new TrafficGenerator(loadBalancer(pool), scheduler);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        assertThatCode(() -> scheduler.awaitTermination(5, TimeUnit.SECONDS))
                .doesNotThrowAnyException();
    }

    // --- traffic-spike -------------------------------------------------------------------------

    @Test
    void applyStartsAPersistentBurstAtTheConfiguredRate() {
        generator.setTargetRate(50L);
        TrafficSpikeScenario scenario = new TrafficSpikeScenario(generator, 200L);

        scenario.apply();

        assertThat(generator.isBursting()).as("the burst is in effect").isTrue();
        assertThat(generator.targetRate()).as("the burst rate is the effective target").isEqualTo(200L);
    }

    @Test
    void revertStopsTheBurstAndRestoresTheBaseRate() {
        generator.setTargetRate(50L);
        TrafficSpikeScenario scenario = new TrafficSpikeScenario(generator, 200L);
        scenario.apply();

        scenario.revert();

        assertThat(generator.isBursting()).as("the burst was stopped").isFalse();
        assertThat(generator.targetRate()).as("the base rate is the effective target again").isEqualTo(50L);
    }

    @Test
    void theRateParamOverridesTheDefault() {
        generator.setTargetRate(50L);
        TrafficSpikeScenario scenario = new TrafficSpikeScenario(generator, 200L);

        scenario.apply(Map.of("rate", "300"));

        assertThat(generator.isBursting()).isTrue();
        assertThat(generator.targetRate()).isEqualTo(300L);
    }

    @Test
    void aNonPositiveRateIsRejectedAndLeavesNoBurstBehind() {
        TrafficSpikeScenario scenario = new TrafficSpikeScenario(generator, 200L);

        assertThatThrownBy(() -> scenario.apply(Map.of("rate", "0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scenario.apply(Map.of("rate", "-5"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(generator.isBursting()).as("a rejected apply started no burst").isFalse();
    }

    @Test
    void aNonPositiveDefaultRateIsRejected() {
        assertThatThrownBy(() -> new TrafficSpikeScenario(generator, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void revertWithoutApplyIsANoOpAndRevertIsIdempotent() {
        generator.setTargetRate(50L);
        TrafficSpikeScenario scenario = new TrafficSpikeScenario(generator, 200L);

        assertThatCode(scenario::revert).doesNotThrowAnyException();
        assertThat(generator.isBursting()).isFalse();

        scenario.apply();
        scenario.revert();
        scenario.revert();

        assertThat(generator.isBursting()).isFalse();
        assertThat(generator.targetRate()).isEqualTo(50L);
    }

    @Test
    void nameAndDescriptionArePinned() {
        TrafficSpikeScenario spike = new TrafficSpikeScenario(generator, 200L);
        CascadingFailureScenario cascade = new CascadingFailureScenario(pool, scheduler);

        assertThat(spike.name()).isEqualTo("traffic-spike");
        assertThat(spike.description()).isNotBlank();
        assertThat(cascade.name()).isEqualTo("cascading-failure");
        assertThat(cascade.description()).isNotBlank();
    }

    @Test
    void theEngineReportsTheSpikeAsActiveWhileItIsApplied() {
        ScenarioEngine engine = new ScenarioEngine(List.of(
                new TrafficSpikeScenario(generator, 200L),
                new CascadingFailureScenario(pool, scheduler)));

        engine.start("traffic-spike", Map.of());

        assertThat(engine.isActive("traffic-spike")).isTrue();
        engine.stop("traffic-spike");
        assertThat(engine.isActive("traffic-spike")).isFalse();
        assertThat(generator.isBursting()).as("stopping the scenario stopped the burst").isFalse();
    }

    // --- cascading-failure ---------------------------------------------------------------------

    @Test
    void theFirstDropLandsImmediatelyAndTheRestFollowOnTheDelay() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 2, CASCADE_DELAY_MS);

        scenario.apply();

        assertThat(countDown()).as("only the first drop has happened when apply returns").isEqualTo(1);
        assertThat(pool.healthy()).hasSize(2);

        awaitCountDown(2, POLL_TIMEOUT_MS);

        assertThat(countDown()).isEqualTo(2);
        assertThat(pool.healthy()).hasSize(1);
    }

    @Test
    void theCascadeDropsDistinctBackends() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 3, CASCADE_DELAY_MS);

        scenario.apply();
        awaitCountDown(3, POLL_TIMEOUT_MS);

        assertThat(pool.all())
                .filteredOn(backend -> backend.status() == BackendStatus.DOWN)
                .extracting(Backend::id)
                .doesNotHaveDuplicates()
                .hasSize(3);
    }

    @Test
    void revertCancelsThePendingDropsAndBringsEveryBackendBackUp() {
        // Five drops from a pool of three at a long delay: apply() drops one and
        // leaves two genuinely pending, so cancel is actually exercised.
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 5, 400L);
        scenario.apply();
        assertThat(countDown()).isEqualTo(1);

        scenario.revert();

        assertThat(allUp()).as("revert brought every backend back").isTrue();
        // A pending drop that had not been cancelled would fire well inside this
        // window, so watching the pool stay quiet proves the cancellation.
        assertStaysAllUp(1_000L);
        assertThat(countDown()).isZero();
    }

    @Test
    void revertAfterTheCascadeHasFinishedStillBringsEverythingBack() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 2, CASCADE_DELAY_MS);
        scenario.apply();
        awaitCountDown(2, POLL_TIMEOUT_MS);

        scenario.revert();

        assertThat(pool.healthy()).extracting(Backend::id).containsExactly("b1", "b2", "b3");
        assertThat(allUp()).isTrue();
    }

    @Test
    void aCountLargerThanThePoolDropsWhatIsLeftWithoutFailing() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 10, CASCADE_DELAY_MS);

        assertThatNoException().isThrownBy(scenario::apply);

        awaitHealthy(0, POLL_TIMEOUT_MS);

        assertThat(countDown()).as("every backend was taken down").isEqualTo(3);
        assertThatNoException().isThrownBy(scenario::revert);
        assertThat(allUp()).isTrue();
    }

    @Test
    void revertRestoresOnlyTheBackendsThisScenarioDropped() {
        Backend alreadyDown = pool.get("b3").orElseThrow();
        alreadyDown.setStatus(BackendStatus.DOWN);
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 2, CASCADE_DELAY_MS);

        scenario.apply();
        awaitCountDown(2, POLL_TIMEOUT_MS);
        scenario.revert();

        assertThat(alreadyDown.status()).as("a pre-existing outage is not silently undone")
                .isEqualTo(BackendStatus.DOWN);
        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void applyingToAPoolWithNothingHealthyIsANoOp() {
        pool.all().forEach(backend -> backend.setStatus(BackendStatus.DOWN));
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 3, CASCADE_DELAY_MS);

        assertThatNoException().isThrownBy(scenario::apply);

        assertStaysAtCountDown(3, 300L);
        assertThatNoException().isThrownBy(scenario::revert);
        assertThat(countDown()).isEqualTo(3);
    }

    @Test
    void revertWithoutApplyIsANoOpAndIsIdempotent() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 3, CASCADE_DELAY_MS);

        assertThatCode(scenario::revert).doesNotThrowAnyException();
        assertThat(allUp()).isTrue();

        scenario.apply();
        awaitCountDown(2, POLL_TIMEOUT_MS);
        scenario.revert();
        scenario.revert();

        assertThat(allUp()).as("a second revert changes nothing").isTrue();
    }

    @Test
    void theScenarioDoesNotShutDownTheSchedulerItWasGiven() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 2, CASCADE_DELAY_MS);

        scenario.apply();
        awaitCountDown(2, POLL_TIMEOUT_MS);
        scenario.revert();

        // A shut-down scheduler rejects work; this one must still accept a task.
        assertThatCode(() -> scheduler.schedule(() -> {
        }, 1, TimeUnit.MILLISECONDS)).doesNotThrowAnyException();
    }

    @Test
    void aCountOfZeroDropsNothing() {
        CascadingFailureScenario scenario = new CascadingFailureScenario(pool, scheduler, 0, CASCADE_DELAY_MS);

        assertThatNoException().isThrownBy(scenario::apply);

        assertStaysAtCountDown(0, 300L);
        assertThatNoException().isThrownBy(scenario::revert);
        assertThat(allUp()).isTrue();
    }

    @Test
    void negativeCountAndDelayAreRejected() {
        assertThatThrownBy(() -> new CascadingFailureScenario(pool, scheduler, -1, CASCADE_DELAY_MS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CascadingFailureScenario(pool, scheduler, 2, -1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CascadingFailureScenario(null, scheduler))
                .isInstanceOf(NullPointerException.class);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Polls the pool until the expected number of backends is DOWN, then fails if it never happens. */
    private void awaitCountDown(int expected, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (countDown() >= expected) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        assertThat(countDown())
                .as("expected %d backends DOWN within %d ms", expected, timeoutMs)
                .isEqualTo(expected);
    }

    /** Polls the pool until the expected number of backends is healthy, then fails if it never happens. */
    private void awaitHealthy(int expected, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (pool.healthy().size() <= expected) {
                return;
            }
            sleep(POLL_INTERVAL_MS);
        }
        assertThat(pool.healthy().size()).as("expected %d healthy backends within %d ms", expected, timeoutMs)
                .isEqualTo(expected);
    }

    /** Re-checks the pool across a window in which a cancelled drop would still have fired. */
    private void assertStaysAllUp(long windowMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            assertThat(allUp()).as("a cancelled drop fired").isTrue();
            sleep(POLL_INTERVAL_MS);
        }
    }

    /** Re-checks the pool across a window in which a scheduled drop would still have fired. */
    private void assertStaysAtCountDown(int expected, long windowMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
        while (System.nanoTime() < deadline) {
            assertThat(countDown()).as("the cascade dropped a backend it should not have").isEqualTo(expected);
            sleep(POLL_INTERVAL_MS);
        }
    }

    private long countDown() {
        return pool.all().stream().filter(backend -> backend.status() == BackendStatus.DOWN).count();
    }

    private boolean allUp() {
        return pool.all().stream().allMatch(backend -> backend.status() == BackendStatus.UP);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling", e);
        }
    }

    private static Backend backend(String id) {
        return new Backend(id, "Backend " + id, CONFIG, id.hashCode());
    }

    private static LoadBalancer loadBalancer(BackendPool pool) {
        LoadBalancer loadBalancer = new LoadBalancer(
                pool, algorithms(), new CircuitBreaker(5, 60_000L), new MetricsCollector());
        loadBalancer.setAlgorithm(RoundRobinStrategy.NAME);
        return loadBalancer;
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
}
