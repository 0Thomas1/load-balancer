package com.example.lb.backend;

import com.example.lb.backend.exceptions.BackendNotFoundException;
import com.example.lb.backend.exceptions.DuplicateBackendException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendPoolTest {

    private static final int THREADS = 4;
    private static final int BACKENDS_PER_THREAD = 1_000;

    private final BackendPool pool = new BackendPool();

    private static Backend backend(String id) {
        return new Backend(id, "Backend " + id, BackendConfig.defaults());
    }

    @Test
    void addKeepsAllBackendsInInsertionOrder() {
        pool.add(backend("b1"));
        pool.add(backend("b2"));

        assertThat(pool.all()).extracting(Backend::id).containsExactly("b1", "b2");
    }

    @Test
    void addRejectsDuplicateId() {
        pool.add(backend("b1"));

        assertThatThrownBy(() -> pool.add(backend("b1")))
                .isInstanceOf(DuplicateBackendException.class);
    }

    @Test
    void removeReturnsBackendAndRemovesItFromPool() {
        pool.add(backend("b1"));
        pool.add(backend("b2"));

        Backend removed = pool.remove("b1");

        assertThat(removed.id()).isEqualTo("b1");
        assertThat(pool.get("b1")).isEmpty();
        assertThat(pool.all()).extracting(Backend::id).containsExactly("b2");
    }

    @Test
    void removeRejectsUnknownId() {
        assertThatThrownBy(() -> pool.remove("missing"))
                .isInstanceOf(BackendNotFoundException.class);
    }

    @Test
    void healthyExcludesBackendsThatAreNotUp() {
        Backend up = backend("b1");
        Backend down = backend("b2");
        Backend draining = backend("b3");
        pool.add(up);
        pool.add(down);
        pool.add(draining);
        down.setStatus(BackendStatus.DOWN);
        draining.setStatus(BackendStatus.DRAINING);

        assertThat(pool.healthy()).extracting(Backend::id).containsExactly("b1");

        up.setStatus(BackendStatus.DOWN);

        assertThat(pool.healthy()).isEmpty();
    }

    @Test
    void concurrentAddsFromManyThreadsRegisterEveryBackend() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < BACKENDS_PER_THREAD; i++) {
                        pool.add(backend("t" + thread + "-b" + i));
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

        assertThat(pool.all()).hasSize(THREADS * BACKENDS_PER_THREAD);
        assertThat(pool.all()).extracting(Backend::id).doesNotHaveDuplicates();
    }
}