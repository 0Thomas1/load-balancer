package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.backend.CircuitState;
import com.example.lb.balancer.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the four config-modifier scenarios. Every case asserts the same two
 * things: {@code apply()} produces the documented state, and {@code revert()}
 * puts the pool back exactly as it was.
 */
class ConfigScenariosTest {

    /** Distinct per backend, so "restored exactly" is a meaningful claim. */
    private static final BackendConfig CONFIG_B1 = new BackendConfig(20, 0.10, 50, 2);
    private static final BackendConfig CONFIG_B2 = new BackendConfig(30, 0.20, 60, 3);
    private static final BackendConfig CONFIG_B3 = new BackendConfig(40, 0.30, 70, 4);

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 60_000L;

    private BackendPool pool;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        pool.add(new Backend("b1", "Backend b1", CONFIG_B1));
        pool.add(new Backend("b2", "Backend b2", CONFIG_B2));
        pool.add(new Backend("b3", "Backend b3", CONFIG_B3));
    }

    private Backend backend(String id) {
        return pool.get(id).orElseThrow();
    }

    /** Marks every backend as unavailable, so scenarios have nothing to pick. */
    private void makeEveryBackendUnhealthy() {
        pool.all().forEach(b -> b.setStatus(BackendStatus.DOWN));
    }

    @Nested
    class LatencyInjection {

        @Test
        void applyAddsTheInjectedLatencyToEveryUpBackend() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "50"));

            assertThat(backend("b1").config().latencyMs()).isEqualTo(70);
            assertThat(backend("b2").config().latencyMs()).isEqualTo(80);
            assertThat(backend("b3").config().latencyMs()).isEqualTo(90);
        }

        @Test
        void applyWithNoParamsUsesTheDefaultInjectMs() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply();

            assertThat(backend("b1").config().latencyMs())
                    .isEqualTo(CONFIG_B1.latencyMs() + LatencyInjectionScenario.DEFAULT_INJECT_MS);
        }

        @Test
        void applyUsesTheConstructorInjectWhenNoParamIsGiven() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool, 5L);

            scenario.apply();

            assertThat(backend("b1").config().latencyMs()).isEqualTo(25);
        }

        @Test
        void applyLeavesEveryOtherConfigFieldAlone() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "10"));

            assertThat(backend("b1").config())
                    .isEqualTo(new BackendConfig(30, CONFIG_B1.failureRate(), CONFIG_B1.capacity(), CONFIG_B1.weight()));
        }

        @Test
        void malformedInjectParamFallsBackToTheDefault() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "not-a-number"));

            assertThat(backend("b1").config().latencyMs())
                    .isEqualTo(CONFIG_B1.latencyMs() + LatencyInjectionScenario.DEFAULT_INJECT_MS);
        }

        @Test
        void injectOfZeroLeavesTheLatencyAlone() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "0"));

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void negativeInjectIsClampedRatherThanRejected() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "-100"));

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void applySkipsBackendsThatAreNotUp() {
            backend("b1").setStatus(BackendStatus.DRAINING);
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            scenario.apply(Map.of("inject", "50"));

            assertThat(backend("b1").config()).isEqualTo(CONFIG_B1);
            assertThat(backend("b2").config().latencyMs()).isEqualTo(80);
        }

        @Test
        void applyWithNothingUpIsASafeNoOp() {
            makeEveryBackendUnhealthy();
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            assertThatNoException().isThrownBy(() -> scenario.apply(Map.of("inject", "50")));

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
            assertThatNoException().isThrownBy(scenario::revert);
            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertRestoresTheOriginalLatencyExactly() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);
            scenario.apply(Map.of("inject", "50"));

            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertWithoutApplyIsANoOp() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            assertThatCode(scenario::revert).doesNotThrowAnyException();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertIsIdempotent() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);
            scenario.apply(Map.of("inject", "50"));

            scenario.revert();
            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void applyingTwiceStillRevertsToTheOriginalLatency() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool, 10L);

            scenario.apply();
            scenario.apply();

            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void aNegativeConstructorInjectIsRejected() {
            assertThatThrownBy(() -> new LatencyInjectionScenario(pool, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nameAndDescriptionAreStable() {
            LatencyInjectionScenario scenario = new LatencyInjectionScenario(pool);

            assertThat(scenario.name()).isEqualTo("latency-injection");
            assertThat(scenario.description()).isNotBlank();
        }
    }

    @Nested
    class NetworkPartition {

        private NetworkPartitionScenario scenario() {
            return new NetworkPartitionScenario(pool, new Random(42));
        }

        private long countDraining() {
            return pool.all().stream().filter(b -> b.status() == BackendStatus.DRAINING).count();
        }

        @Test
        void applyMarksCountBackendsDrainingAndLeavesTheRestUp() {
            NetworkPartitionScenario scenario = scenario();

            scenario.apply(Map.of("count", "1"));

            assertThat(countDraining()).isEqualTo(1);
            assertThat(pool.healthy()).hasSize(2);
        }

        @Test
        void applyWithNoParamsPartitionsASingleBackend() {
            NetworkPartitionScenario scenario = scenario();

            scenario.apply();

            assertThat(countDraining()).isEqualTo(1);
        }

        @Test
        void applyPartitionsDistinctBackendsWithoutReplacement() {
            NetworkPartitionScenario scenario = scenario();

            scenario.apply(Map.of("count", "2"));

            assertThat(pool.all()).filteredOn(b -> b.status() == BackendStatus.DRAINING)
                    .extracting(Backend::id).doesNotHaveDuplicates().hasSize(2);
        }

        @Test
        void countLargerThanThePoolClampsToThePoolSize() {
            NetworkPartitionScenario scenario = scenario();

            assertThatNoException().isThrownBy(() -> scenario.apply(Map.of("count", "5")));

            assertThat(countDraining()).isEqualTo(3);
            assertThat(pool.healthy()).isEmpty();
        }

        @Test
        void malformedCountParamFallsBackToTheDefault() {
            NetworkPartitionScenario scenario = scenario();

            scenario.apply(Map.of("count", "not-a-number"));

            assertThat(countDraining()).isEqualTo(1);
        }

        @Test
        void applyOnlyPicksBackendsThatAreUp() {
            backend("b1").setStatus(BackendStatus.DRAINING);
            NetworkPartitionScenario scenario = scenario();

            scenario.apply(Map.of("count", "2"));

            // b1 was already draining, so it is not re-marked and the partition
            // takes two of the two remaining UP backends.
            assertThat(countDraining()).isEqualTo(3);
            assertThat(pool.healthy()).isEmpty();
        }

        @Test
        void applyWithNothingUpIsASafeNoOp() {
            makeEveryBackendUnhealthy();
            NetworkPartitionScenario scenario = scenario();

            assertThatNoException().isThrownBy(() -> scenario.apply(Map.of("count", "2")));

            assertThat(pool.all()).extracting(Backend::status)
                    .containsOnly(BackendStatus.DOWN);
            assertThatNoException().isThrownBy(scenario::revert);
            assertThat(pool.all()).extracting(Backend::status)
                    .containsOnly(BackendStatus.DOWN);
        }

        @Test
        void revertBringsEveryPartitionedBackendBackUp() {
            NetworkPartitionScenario scenario = scenario();
            scenario.apply(Map.of("count", "2"));

            scenario.revert();

            assertThat(pool.all()).extracting(Backend::status).containsOnly(BackendStatus.UP);
            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertWithoutApplyIsANoOp() {
            NetworkPartitionScenario scenario = scenario();

            assertThatCode(scenario::revert).doesNotThrowAnyException();

            assertThat(pool.all()).extracting(Backend::status).containsOnly(BackendStatus.UP);
        }

        @Test
        void revertIsIdempotent() {
            NetworkPartitionScenario scenario = scenario();
            scenario.apply(Map.of("count", "3"));

            scenario.revert();
            scenario.revert();

            assertThat(pool.all()).extracting(Backend::status).containsOnly(BackendStatus.UP);
        }

        @Test
        void nameAndDescriptionAreStable() {
            NetworkPartitionScenario scenario = scenario();

            assertThat(scenario.name()).isEqualTo("network-partition");
            assertThat(scenario.description()).isNotBlank();
        }
    }

    @Nested
    class SlowBackend {

        private SlowBackendScenario scenario() {
            return new SlowBackendScenario(pool);
        }

        @Test
        void applyMakesOneBackendSlowAndCapacityConstrained() {
            SlowBackendScenario scenario = scenario();

            scenario.apply();

            assertThat(backend("b1").config().latencyMs()).isEqualTo(SlowBackendScenario.SLOW_LATENCY_MS);
            assertThat(backend("b1").config().capacity()).isEqualTo(SlowBackendScenario.SLOW_CAPACITY);
        }

        @Test
        void applyLeavesTheOtherBackendsAlone() {
            SlowBackendScenario scenario = scenario();

            scenario.apply(Map.of("id", "b2"));

            assertThat(backend("b1").config()).isEqualTo(CONFIG_B1);
            assertThat(backend("b3").config()).isEqualTo(CONFIG_B3);
        }

        @Test
        void applyKeepsTheOtherConfigFieldsOfTheTargetBackend() {
            SlowBackendScenario scenario = scenario();

            scenario.apply(Map.of("id", "b2"));

            assertThat(backend("b2").config()).isEqualTo(
                    new BackendConfig(SlowBackendScenario.SLOW_LATENCY_MS, CONFIG_B2.failureRate(),
                            SlowBackendScenario.SLOW_CAPACITY, CONFIG_B2.weight()));
        }

        @Test
        void applyWithAnUnknownIdFails() {
            SlowBackendScenario scenario = scenario();

            assertThatThrownBy(() -> scenario.apply(Map.of("id", "nope")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nope");
        }

        @Test
        void applyWithNothingUpIsASafeNoOp() {
            makeEveryBackendUnhealthy();
            SlowBackendScenario scenario = scenario();

            assertThatNoException().isThrownBy(scenario::apply);

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
            assertThatNoException().isThrownBy(scenario::revert);
            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertRestoresBothLatencyAndCapacity() {
            SlowBackendScenario scenario = scenario();
            scenario.apply(Map.of("id", "b3"));

            scenario.revert();

            assertThat(backend("b3").config()).isEqualTo(CONFIG_B3);
        }

        @Test
        void revertRestoresTheBackendItActuallyPicked() {
            SlowBackendScenario scenario = scenario();
            scenario.apply();

            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertWithoutApplyIsANoOp() {
            SlowBackendScenario scenario = scenario();

            assertThatCode(scenario::revert).doesNotThrowAnyException();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertIsIdempotent() {
            SlowBackendScenario scenario = scenario();
            scenario.apply();

            scenario.revert();
            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void nameAndDescriptionAreStable() {
            SlowBackendScenario scenario = scenario();

            assertThat(scenario.name()).isEqualTo("slow-backend");
            assertThat(scenario.description()).isNotBlank();
        }
    }

    @Nested
    class CircuitBreakerTrip {

        private CircuitBreaker breaker;

        @BeforeEach
        void setUpBreaker() {
            breaker = new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS);
        }

        private CircuitBreakerTripScenario scenario() {
            return new CircuitBreakerTripScenario(pool, breaker);
        }

        @Test
        void applyMakesOneBackendAlwaysFailAndOpensItsCircuit() {
            CircuitBreakerTripScenario scenario = scenario();

            scenario.apply();

            assertThat(backend("b1").config().failureRate()).isEqualTo(1.0);
            assertThat(breaker.state("b1")).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void applyOpensTheCircuitWhateverTheFailureThresholdIs() {
            CircuitBreaker strictBreaker = new CircuitBreaker(50, RESET_TIMEOUT_MS);
            CircuitBreakerTripScenario scenario = new CircuitBreakerTripScenario(pool, strictBreaker);

            scenario.apply();

            assertThat(strictBreaker.state("b1")).isEqualTo(CircuitState.OPEN);
        }

        @Test
        void applyToleratesABreakerWithNoResetTimeout() {
            CircuitBreakerTripScenario scenario =
                    new CircuitBreakerTripScenario(pool, new CircuitBreaker(FAILURE_THRESHOLD, 0L));

            assertThatNoException().isThrownBy(scenario::apply);

            assertThat(backend("b1").config().failureRate()).isEqualTo(1.0);
        }

        @Test
        void applyWithIdParamSelectsThatBackend() {
            CircuitBreakerTripScenario scenario = scenario();

            scenario.apply(Map.of("id", "b3"));

            assertThat(backend("b3").config().failureRate()).isEqualTo(1.0);
            assertThat(breaker.state("b3")).isEqualTo(CircuitState.OPEN);
            assertThat(breaker.state("b1")).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void applyWithAnUnknownIdFails() {
            CircuitBreakerTripScenario scenario = scenario();

            assertThatThrownBy(() -> scenario.apply(Map.of("id", "nope")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nope");
        }

        @Test
        void applyLeavesEveryOtherConfigFieldAlone() {
            CircuitBreakerTripScenario scenario = scenario();

            scenario.apply(Map.of("id", "b2"));

            assertThat(backend("b2").config()).isEqualTo(CONFIG_B2.copyWith(null, 1.0, null, null));
        }

        @Test
        void applyWithNothingUpIsASafeNoOp() {
            makeEveryBackendUnhealthy();
            CircuitBreakerTripScenario scenario = scenario();

            assertThatNoException().isThrownBy(scenario::apply);

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
            assertThat(breaker.state("b1")).isEqualTo(CircuitState.CLOSED);
            assertThatNoException().isThrownBy(scenario::revert);
        }

        @Test
        void revertRestoresTheFailureRateAndClosesTheCircuit() {
            CircuitBreakerTripScenario scenario = scenario();
            scenario.apply(Map.of("id", "b2"));

            scenario.revert();

            assertThat(backend("b2").config()).isEqualTo(CONFIG_B2);
            assertThat(breaker.state("b2")).isEqualTo(CircuitState.CLOSED);
        }

        @Test
        void revertOnlyTouchesTheBackendItTripped() {
            CircuitBreakerTripScenario scenario = scenario();
            scenario.apply(Map.of("id", "b2"));

            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertWithoutApplyIsANoOp() {
            CircuitBreakerTripScenario scenario = scenario();

            assertThatCode(scenario::revert).doesNotThrowAnyException();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
        }

        @Test
        void revertIsIdempotent() {
            CircuitBreakerTripScenario scenario = scenario();
            scenario.apply();

            scenario.revert();
            scenario.revert();

            assertThat(pool.all()).extracting(Backend::config)
                    .containsExactly(CONFIG_B1, CONFIG_B2, CONFIG_B3);
            assertThat(pool.all()).map(Backend::id).allSatisfy(id ->
                    assertThat(breaker.state(id)).isEqualTo(CircuitState.CLOSED));
        }

        @Test
        void nameAndDescriptionAreStable() {
            CircuitBreakerTripScenario scenario = scenario();

            assertThat(scenario.name()).isEqualTo("circuit-breaker-trip");
            assertThat(scenario.description()).isNotBlank();
        }

        @Test
        void applyIsUsableThroughTheScenarioInterface() {
            Scenario asInterface = scenario();

            asInterface.apply();

            assertThat(pool.all()).filteredOn(b -> b.config().failureRate() == 1.0).hasSize(1);
            assertThat(List.of(breaker.state("b1"), breaker.state("b2"), breaker.state("b3")))
                    .contains(CircuitState.OPEN, CircuitState.CLOSED, CircuitState.CLOSED);
        }
    }
}