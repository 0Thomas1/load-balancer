package com.example.lb.metrics;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import com.example.lb.backend.CircuitState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    private static final long LONG_WINDOW_MS = 10_000L;
    private static final int THREADS = 4;
    private static final int REQUESTS_PER_THREAD = 1_000;

    @Test
    void successfulRequestsIncrementTotalRequestsAndWindow() {
        MetricsCollector collector = new MetricsCollector(LONG_WINDOW_MS);

        for (int i = 0; i < 10; i++) {
            collector.record(true);
        }

        assertThat(collector.totalRequests()).isEqualTo(10);
        assertThat(collector.errorCount()).isZero();
        assertThat(collector.requestsPerSecond()).isEqualTo(10);
    }

    @Test
    void failedRequestsIncrementErrorCount() {
        MetricsCollector collector = new MetricsCollector(LONG_WINDOW_MS);

        for (int i = 0; i < 3; i++) {
            collector.record(false);
        }

        assertThat(collector.errorCount()).isEqualTo(3);
    }

    @Test
    void windowDecaysArrivalsOlderThanTheWindow() throws Exception {
        MetricsCollector collector = new MetricsCollector(50);

        collector.record(true);
        Thread.sleep(60);
        collector.record(true);

        assertThat(collector.requestsPerSecond()).isEqualTo(1);
    }

    @Test
    void snapshotComposesCountersAlgorithmScenariosAndBackendViews() {
        MetricsCollector collector = new MetricsCollector(LONG_WINDOW_MS);
        BackendPool pool = new BackendPool();
        Backend up = backend("b1", new BackendConfig(10, 0.0, 100, 3));
        Backend down = backend("b2", BackendConfig.defaults());
        pool.add(up);
        pool.add(down);

        up.stats().incrementActive();
        up.stats().recordSuccess(4_000_000L);
        up.stats().recordFailure(2_000_000L);
        up.stats().setCircuitState(CircuitState.HALF_OPEN);
        down.setStatus(BackendStatus.DOWN);

        collector.record(true);
        collector.record(false);

        long before = System.currentTimeMillis();
        MetricsSnapshot snapshot =
                collector.snapshot(pool, "round-robin", List.of("consumer-drop"));
        long after = System.currentTimeMillis();

        assertThat(snapshot.activeAlgorithm()).isEqualTo("round-robin");
        assertThat(snapshot.activeScenarios()).containsExactly("consumer-drop");
        assertThat(snapshot.totalRequests()).isEqualTo(2);
        assertThat(snapshot.errorCount()).isEqualTo(1);
        assertThat(snapshot.requestsPerSecond()).isEqualTo(2);
        assertThat(snapshot.timestamp()).isBetween(before, after);

        assertThat(snapshot.backends()).hasSize(2);
        assertThat(snapshot.backends().get(0)).isEqualTo(BackendView.from(up));
        assertThat(snapshot.backends().get(0).id()).isEqualTo("b1");
        assertThat(snapshot.backends().get(0).name()).isEqualTo("Backend b1");
        assertThat(snapshot.backends().get(0).status()).isEqualTo(BackendStatus.UP);
        assertThat(snapshot.backends().get(0).weight()).isEqualTo(3);
        assertThat(snapshot.backends().get(0).activeConnections()).isEqualTo(1);
        assertThat(snapshot.backends().get(0).totalRequests()).isEqualTo(2);
        assertThat(snapshot.backends().get(0).errorCount()).isEqualTo(1);
        assertThat(snapshot.backends().get(0).avgLatency()).isEqualTo(3.0);
        assertThat(snapshot.backends().get(0).circuitState()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(snapshot.backends().get(1).id()).isEqualTo("b2");
        assertThat(snapshot.backends().get(1).status()).isEqualTo(BackendStatus.DOWN);
    }

    @Test
    void snapshotCopiesTheScenarioListDefensively() {
        MetricsCollector collector = new MetricsCollector(LONG_WINDOW_MS);
        List<String> scenarios = new ArrayList<>(List.of("consumer-drop"));

        MetricsSnapshot snapshot = collector.snapshot(new BackendPool(), "random", scenarios);
        scenarios.clear();

        assertThat(snapshot.activeScenarios()).containsExactly("consumer-drop");
    }

    @Test
    void concurrentRecordsFromManyThreadsKeepCountersExact() throws Exception {
        MetricsCollector collector = new MetricsCollector(LONG_WINDOW_MS);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        collector.record(i % 2 == 0);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(collector.totalRequests()).isEqualTo(THREADS * REQUESTS_PER_THREAD);
        assertThat(collector.errorCount()).isEqualTo(THREADS * REQUESTS_PER_THREAD / 2);
        assertThat(collector.requestsPerSecond()).isEqualTo(THREADS * REQUESTS_PER_THREAD);
    }

    private static Backend backend(String id, BackendConfig config) {
        return new Backend(id, "Backend " + id, config);
    }
}