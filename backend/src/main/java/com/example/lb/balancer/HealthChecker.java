package com.example.lb.balancer;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.example.lb.backend.BackendStatus.DOWN;
import static com.example.lb.backend.BackendStatus.UP;

/**
 * Probes the pool's backends and takes overloaded or unresponsive ones out of
 * rotation.
 *
 * <p>A probe fails when the backend is at capacity ({@code activeConnections >=
 * capacity}) or slower than the configured {@code probeTimeoutMs}. An
 * {@link BackendStatus#UP} backend that fails {@code failureThreshold} probes
 * in a row flips to {@link BackendStatus#DOWN}; a single successful probe
 * clears its streak.
 *
 * <p>Recovery is <em>explicit</em>: only {@link #recover(Backend)} brings a DOWN
 * backend back UP. Probing never resurrects one, and DOWN backends are not
 * probed at all. Automatic recovery would silently undo the scenarios in which
 * a backend is taken down on purpose (Consumer Drop, Circuit Breaker Trip); the
 * backend-recovery scenario is the recovery mechanism instead.
 *
 * <p>{@link BackendStatus#DRAINING} backends are skipped entirely: they are
 * being taken out of rotation deliberately, so their health is nobody's
 * business.
 *
 * <p>Thread-safe: the consecutive-failure counts live in a
 * {@link ConcurrentHashMap}, and all mutations of a backend's status go through
 * its own atomic status reference.
 */
public class HealthChecker implements AutoCloseable {

    private static final long DEFAULT_PROBE_INTERVAL_MS = 2_000;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final long DEFAULT_PROBE_TIMEOUT_MS = 500;

    private final BackendPool pool;
    private final long probeIntervalMs;
    private final int failureThreshold;
    private final long probeTimeoutMs;
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public HealthChecker(BackendPool pool) {
        this(pool, DEFAULT_PROBE_INTERVAL_MS, DEFAULT_FAILURE_THRESHOLD, DEFAULT_PROBE_TIMEOUT_MS);
    }

    public HealthChecker(BackendPool pool, long probeIntervalMs, int failureThreshold, long probeTimeoutMs) {
        if (probeIntervalMs < 1) {
            throw new IllegalArgumentException("probeIntervalMs must be >= 1");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (probeTimeoutMs < 1) {
            throw new IllegalArgumentException("probeTimeoutMs must be >= 1");
        }
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.probeIntervalMs = probeIntervalMs;
        this.failureThreshold = failureThreshold;
        this.probeTimeoutMs = probeTimeoutMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "health-checker");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Whether the backend currently answers its health probe: it fails when it
     * is at capacity or when its configured latency reaches the probe timeout.
     */
    public boolean probe(Backend backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        if (backend.stats().activeConnections() >= backend.config().capacity()) {
            return false;
        }
        return backend.config().latencyMs() < probeTimeoutMs;
    }

    /**
     * Probes every backend in the pool once.
     *
     * <p>DRAINING backends are skipped and DOWN backends are not probed, so
     * only UP backends can be flipped to DOWN here.
     */
    public void probeAll() {
        for (Backend backend : pool.all()) {
            if (backend.status() != UP) {
                continue;
            }
            if (probe(backend)) {
                consecutiveFailures.remove(backend.id());
            } else if (recordFailure(backend) >= failureThreshold) {
                backend.setStatus(DOWN);
            }
        }
    }

    /** Brings the backend back into rotation and clears its failure streak. */
    public void recover(Backend backend) {
        Objects.requireNonNull(backend, "backend must not be null");
        backend.setStatus(UP);
        consecutiveFailures.remove(backend.id());
    }

    /** Starts probing the pool on a daemon thread every {@code probeIntervalMs}. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::probeAll, probeIntervalMs, probeIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the probe schedule and releases the scheduler thread. A closed
     * checker cannot be restarted; its {@link #probeAll()} and
     * {@link #recover(Backend)} methods remain usable, so callers that want to
     * keep probing should schedule those themselves.
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private int recordFailure(Backend backend) {
        return consecutiveFailures.merge(backend.id(), 1, Integer::sum);
    }
}