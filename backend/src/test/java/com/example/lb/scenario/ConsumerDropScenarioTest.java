package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ConsumerDropScenarioTest {

    private static final BackendConfig CONFIG = new BackendConfig(7, 0.25, 42, 3);

    private BackendPool pool;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        pool.add(backend("b1"));
        pool.add(backend("b2"));
        pool.add(backend("b3"));
    }

    private static Backend backend(String id) {
        return new Backend(id, "Backend " + id, CONFIG);
    }

    private ConsumerDropScenario scenarioWithSeed(long seed) {
        return new ConsumerDropScenario(pool, new Random(seed));
    }

    private long countDown() {
        return pool.all().stream().filter(b -> b.status() == BackendStatus.DOWN).count();
    }

    @Test
    void countLargerThanTheHealthyPoolClampsToTheHealthyPoolSize() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        scenario.apply(Map.of("count", "5"));

        assertThat(countDown()).isEqualTo(3);
        assertThat(pool.healthy()).isEmpty();
    }

    @Test
    void applyDropsExactlyCountBackendsAndLeavesTheOthersUp() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        scenario.apply(Map.of("count", "1"));

        assertThat(countDown()).isEqualTo(1);
        assertThat(pool.healthy()).hasSize(2);
    }

    @Test
    void applyDropsDistinctBackendsWithoutReplacement() {
        ConsumerDropScenario scenario = scenarioWithSeed(7);

        scenario.apply(Map.of("count", "2"));

        assertThat(pool.all()).filteredOn(b -> b.status() == BackendStatus.DOWN)
                .extracting(Backend::id).doesNotHaveDuplicates().hasSize(2);
    }

    @Test
    void applyWithNoParamsDropsASingleBackend() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        scenario.apply();

        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void applyOnAnEmptyPoolIsASafeNoOp() {
        pool.all().forEach(b -> b.setStatus(BackendStatus.DOWN));
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        assertThatNoException().isThrownBy(() -> scenario.apply(Map.of("count", "2")));

        assertThat(countDown()).isEqualTo(3);
        assertThatNoException().isThrownBy(scenario::revert);
        assertThat(countDown()).isEqualTo(3);
    }

    @Test
    void malformedCountParamFallsBackToTheDefault() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        scenario.apply(Map.of("count", "not-a-number"));

        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void revertBringsBackEveryDroppedBackendAndLeavesConfigUntouched() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);
        scenario.apply(Map.of("count", "2"));

        scenario.revert();

        assertThat(pool.healthy()).extracting(Backend::id).containsExactly("b1", "b2", "b3");
        assertThat(pool.all()).extracting(Backend::config).containsOnly(CONFIG);
    }

    @Test
    void revertOnlyRestoresTheBackendsThisScenarioDropped() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);
        scenario.apply(Map.of("count", "1"));
        Backend survivor = pool.all().stream()
                .filter(b -> b.status() == BackendStatus.UP).findFirst().orElseThrow();
        survivor.setStatus(BackendStatus.DRAINING);

        scenario.revert();

        assertThat(survivor.status()).isEqualTo(BackendStatus.DRAINING);
        assertThat(countDown()).isZero();
    }

    @Test
    void revertWithoutApplyIsANoOp() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        assertThatCode(scenario::revert).doesNotThrowAnyException();

        assertThat(pool.all()).extracting(Backend::status).containsOnly(BackendStatus.UP);
    }

    @Test
    void revertIsIdempotent() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);
        scenario.apply(Map.of("count", "2"));

        scenario.revert();
        scenario.revert();

        assertThat(pool.healthy()).hasSize(3);
    }

    @Test
    void nameAndDescriptionAreStable() {
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        assertThat(scenario.name()).isEqualTo("consumer-drop");
        assertThat(scenario.description()).isNotBlank();
    }

    @Test
    void applyOverAnAlreadyDroppedPoolIsANoOp() {
        pool.all().forEach(b -> b.setStatus(BackendStatus.DOWN));
        ConsumerDropScenario scenario = scenarioWithSeed(42);

        scenario.apply(Map.of("count", "2"));

        assertThat(countDown()).isEqualTo(3);
    }

    }