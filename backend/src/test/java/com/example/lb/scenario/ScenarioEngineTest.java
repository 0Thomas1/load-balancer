package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.scenario.exceptions.ScenarioAlreadyActiveException;
import com.example.lb.scenario.exceptions.ScenarioNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioEngineTest {

    private BackendPool pool;
    private ScenarioEngine engine;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        pool.add(backend("b1"));
        pool.add(backend("b2"));
        pool.add(backend("b3"));
        CircuitBreaker breaker = new CircuitBreaker(2, 60_000L);
        engine = new ScenarioEngine(List.of(
                new ConsumerDropScenario(pool, new Random(42)),
                new BackendRecoveryScenario(pool, breaker, new Random(42))));
    }

    private static Backend backend(String id) {
        return new Backend(id, "Backend " + id, BackendConfig.defaults());
    }

    private long countDown() {
        return pool.all().stream().filter(b -> b.status() == BackendStatus.DOWN).count();
    }

    @Test
    void listReturnsRegistrySortedByNameAndInactiveInitially() {
        List<ScenarioInfo> scenarios = engine.list();

        assertThat(scenarios).extracting(ScenarioInfo::name)
                .containsExactly("backend-recovery", "consumer-drop");
        assertThat(scenarios).extracting(ScenarioInfo::description)
                .allSatisfy(description -> assertThat(description).isNotBlank());
        assertThat(scenarios).extracting(ScenarioInfo::active).containsOnly(false);
    }

    @Test
    void startRejectsUnknownScenarioName() {
        assertThatThrownBy(() -> engine.start("does-not-exist", null))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    @Test
    void startConsumerDropWithCountParamMarksExactlyThatManyBackendsDown() {
        engine.start("consumer-drop", Map.of("count", "2"));

        assertThat(engine.isActive("consumer-drop")).isTrue();
        assertThat(countDown()).isEqualTo(2);
    }

    @Test
    void startingAnAlreadyActiveScenarioFails() {
        engine.start("consumer-drop", Map.of("count", "1"));

        assertThatThrownBy(() -> engine.start("consumer-drop", Map.of("count", "1")))
                .isInstanceOf(ScenarioAlreadyActiveException.class);
        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void stopRestoresBackendsAndClearsTheActiveFlag() {
        engine.start("consumer-drop", Map.of("count", "2"));

        engine.stop("consumer-drop");

        assertThat(engine.isActive("consumer-drop")).isFalse();
        assertThat(pool.healthy()).hasSize(3);
        assertThat(engine.list()).filteredOn(ScenarioInfo::active).isEmpty();
    }

    @Test
    void startWithoutParamsUsesScenarioDefaults() {
        engine.start("consumer-drop", null);

        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void startWithEmptyParamsUsesScenarioDefaults() {
        engine.start("consumer-drop", Map.of());

        assertThat(countDown()).isEqualTo(1);
    }

    @Test
    void startAndStopNeverDriveTrafficThemselves() {
        engine.start("consumer-drop", Map.of("count", "2"));
        engine.stop("consumer-drop");

        assertThat(pool.all()).allSatisfy(backend ->
                assertThat(backend.stats().totalRequests()).isZero());
    }

    @Test
    void stopAllRevertsEveryActiveScenario() {
        engine.start("consumer-drop", Map.of("count", "3"));
        engine.start("backend-recovery", null);
        assertThat(countDown()).isEqualTo(2);

        engine.stopAll();

        assertThat(engine.list()).filteredOn(ScenarioInfo::active).isEmpty();
        assertThat(pool.all()).allSatisfy(backend ->
                assertThat(backend.status()).isEqualTo(BackendStatus.UP));
    }

    @Test
    void stopRejectsUnknownScenarioName() {
        assertThatThrownBy(() -> engine.stop("does-not-exist"))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    @Test
    void isActiveRejectsUnknownScenarioName() {
        assertThatThrownBy(() -> engine.isActive("does-not-exist"))
                .isInstanceOf(ScenarioNotFoundException.class);
    }

    @Test
    void registryRejectsTwoScenariosWithTheSameName() {
        assertThatThrownBy(() -> new ScenarioEngine(List.of(
                new ConsumerDropScenario(pool, new Random(1)),
                new ConsumerDropScenario(pool, new Random(2)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}