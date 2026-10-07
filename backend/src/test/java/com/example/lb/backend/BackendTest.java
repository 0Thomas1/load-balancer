package com.example.lb.backend;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class BackendTest {

    private static final long MS_TO_NANOS = 1_000_000L;

    @Test
    void simulateSucceedsWithZeroFailureRate() {
        Backend backend = new Backend("b1", "Backend 1", new BackendConfig(0, 0.0, 100, 1), 42L);

        SimulationResult result = backend.simulate();

        assertThat(result.success()).isTrue();
        assertThat(result.reason()).isEqualTo(FailureReason.NONE);
    }

    @Test
    void simulateFailsWithFullFailureRate() {
        Backend backend = new Backend("b1", "Backend 1", new BackendConfig(0, 1.0, 100, 1), 42L);

        SimulationResult result = backend.simulate();

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).isEqualTo(FailureReason.BACKEND_FAILED);
    }

    @Test
    void simulateFailsWithCapacityExceededWhileAnotherThreadHoldsConnection() throws Exception {
        Backend backend = new Backend("b1", "Backend 1", new BackendConfig(0, 0.0, 1, 1), 42L);
        backend.stats().incrementActive();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Callable<SimulationResult> call = backend::simulate;
            Future<SimulationResult> future = executor.submit(call);

            SimulationResult result = future.get();

            assertThat(result.success()).isFalse();
            assertThat(result.reason()).isEqualTo(FailureReason.CAPACITY_EXCEEDED);
            assertThat(result.latencyNanos()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void simulateReportsLatencyInNanos() {
        Backend backend = new Backend("b1", "Backend 1", new BackendConfig(10, 0.0, 100, 1), 42L);

        SimulationResult result = backend.simulate();

        assertThat(result.success()).isTrue();
        assertThat(result.latencyNanos()).isGreaterThanOrEqualTo(10 * MS_TO_NANOS);
    }

    @Test
    void statsTrackActiveConnectionsRequestsErrorsAndLatency() {
        BackendStats stats = new BackendStats();

        stats.incrementActive();
        stats.incrementActive();
        stats.recordSuccess(10 * MS_TO_NANOS);
        stats.recordFailure(30 * MS_TO_NANOS);
        stats.decrementActive();

        assertThat(stats.activeConnections()).isEqualTo(1);
        assertThat(stats.totalRequests()).isEqualTo(2);
        assertThat(stats.errorCount()).isEqualTo(1);
        assertThat(stats.avgLatencyMs()).isEqualTo(20.0);
    }

    @Test
    void avgLatencyIsZeroWithoutRequestsAndCircuitStateDefaultsToClosed() {
        BackendStats stats = new BackendStats();

        assertThat(stats.avgLatencyMs()).isZero();
        assertThat(stats.circuitState()).isEqualTo(CircuitState.CLOSED);

        stats.setCircuitState(CircuitState.OPEN);

        assertThat(stats.circuitState()).isEqualTo(CircuitState.OPEN);
    }

    @Test
    void backendExposesIdentityAndMutableConfigAndStatus() {
        Backend backend = new Backend("b1", "Backend 1", BackendConfig.defaults(), 42L);

        assertThat(backend.id()).isEqualTo("b1");
        assertThat(backend.name()).isEqualTo("Backend 1");
        assertThat(backend.status()).isEqualTo(BackendStatus.UP);
        assertThat(backend.config()).isEqualTo(BackendConfig.defaults());
        assertThat(backend.stats()).isNotNull();

        BackendConfig updated = backend.config().copyWith(null, null, 10, null);
        backend.updateConfig(updated);
        backend.setStatus(BackendStatus.DRAINING);

        assertThat(backend.config().capacity()).isEqualTo(10);
        assertThat(backend.status()).isEqualTo(BackendStatus.DRAINING);
    }
}