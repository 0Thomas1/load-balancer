package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.backend.CircuitState;
import com.example.lb.balancer.CircuitBreaker;
import com.example.lb.scenario.exceptions.ScenarioPreconditionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendRecoveryScenarioTest {

    private static final int FAILURE_THRESHOLD = 2;
    private static final long RESET_TIMEOUT_MS = 60_000L;

    private BackendPool pool;
    private CircuitBreaker breaker;
    private BackendRecoveryScenario scenario;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        pool.add(newBackend("b1"));
        pool.add(newBackend("b2"));
        pool.add(newBackend("b3"));
        breaker = new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS);
        scenario = new BackendRecoveryScenario(pool, breaker, new Random(42));
    }

    private static Backend newBackend(String id) {
        return new Backend(id, "Backend " + id, BackendConfig.defaults());
    }

    /** The pooled backend with the given id. */
    private Backend backend(String id) {
        return pool.get(id).orElseThrow();
    }

    private void tripCircuit(String backendId) {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.onFailure(backendId);
        }
        assertThat(breaker.isOpen(backendId)).isTrue();
    }

    @Test
    void applyWithNoDownBackendFails() {
        assertThatThrownBy(scenario::apply)
                .isInstanceOf(ScenarioPreconditionException.class)
                .hasMessageContaining("no backend is DOWN");
    }

    @Test
    void applyBringsTheOnlyDownBackendUpAndClosesItsCircuit() {
        tripCircuit("b1");
        backend("b1").setStatus(BackendStatus.DOWN);

        scenario.apply();

        assertThat(backend("b1").status()).isEqualTo(BackendStatus.UP);
        assertThat(breaker.state("b1")).isEqualTo(CircuitState.CLOSED);
        assertThat(pool.healthy()).hasSize(3);
    }

    @Test
    void applyRecoversOneBackendAtATimeLeavingTheOthersDown() {
        for (String id : List.of("b1", "b2")) {
            tripCircuit(id);
            backend(id).setStatus(BackendStatus.DOWN);
        }

        scenario.apply();

        List<Backend> up = pool.healthy();
        assertThat(up).hasSize(2);
        // Exactly the backend that came back up has its circuit closed; the
        // other stays down with its circuit open, so recovery is not a blanket reset.
        Backend recovered = up.stream().filter(b -> !b.id().equals("b3")).findFirst().orElseThrow();
        Backend stillDown = pool.all().stream()
                .filter(b -> b.status() == BackendStatus.DOWN).findFirst().orElseThrow();
        assertThat(breaker.state(recovered.id())).isEqualTo(CircuitState.CLOSED);
        assertThat(breaker.state(stillDown.id())).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void applyOnlyTouchesTheBackendItRecovers() {
        tripCircuit("b2");
        backend("b1").setStatus(BackendStatus.DOWN);
        Backend untouched = backend("b2");

        scenario.apply();

        assertThat(backend("b1").status()).isEqualTo(BackendStatus.UP);
        assertThat(untouched.status()).isEqualTo(BackendStatus.UP);
        assertThat(untouched.config()).isEqualTo(BackendConfig.defaults());
    }

    @Test
    void applyIgnoresBackendsThatAreNotDown() {
        backend("b1").setStatus(BackendStatus.DRAINING);
        backend("b2").setStatus(BackendStatus.DOWN);

        scenario.apply();

        assertThat(backend("b2").status()).isEqualTo(BackendStatus.UP);
        assertThat(backend("b1").status()).isEqualTo(BackendStatus.DRAINING);
    }

    @Test
    void revertIsANoOp() {
        backend("b1").setStatus(BackendStatus.DOWN);

        assertThatCode(scenario::revert).doesNotThrowAnyException();

        assertThat(backend("b1").status()).isEqualTo(BackendStatus.DOWN);
        assertThat(backend("b1").stats().totalRequests()).isZero();
    }

    @Test
    void nameAndDescriptionAreStable() {
        assertThat(scenario.name()).isEqualTo("backend-recovery");
        assertThat(scenario.description()).isNotBlank();
    }

    @Test
    void applyIsUsableThroughTheScenarioInterface() {
        Scenario asInterface = scenario;
        backend("b3").setStatus(BackendStatus.DOWN);

        asInterface.apply();

        assertThat(backend("b3").status()).isEqualTo(BackendStatus.UP);
    }

    @Test
    void applyWithSeveralDownBackendsIsRepeatable() {
        backend("b1").setStatus(BackendStatus.DOWN);
        backend("b2").setStatus(BackendStatus.DOWN);
        backend("b3").setStatus(BackendStatus.DOWN);

        scenario.apply();
        scenario.apply();
        scenario.apply();

        List<Backend> up = pool.healthy();
        assertThat(up).hasSize(3);
    }
}