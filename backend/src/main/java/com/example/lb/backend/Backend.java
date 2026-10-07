package com.example.lb.backend;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** A simulated backend that responds with configurable latency and failure rate. */
public class Backend {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final AtomicReference<BackendConfig> config;
    private final AtomicReference<BackendStatus> status = new AtomicReference<>(BackendStatus.UP);
    private final BackendStats stats = new BackendStats();
    private final String id;
    private final String name;
    private final Random random;

    public Backend(String id, String name, BackendConfig config) {
        this(id, name, config, new Random(System.nanoTime()));
    }

    /** Constructor with a fixed random seed, for deterministic tests. */
    public Backend(String id, String name, BackendConfig config, long randomSeed) {
        this(id, name, config, new Random(randomSeed));
    }

    private Backend(String id, String name, BackendConfig config, Random random) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.config = new AtomicReference<>(Objects.requireNonNull(config, "config must not be null"));
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Simulates one request: rejects when the backend is at capacity, then fails
     * with the configured failure rate, otherwise sleeps for the configured latency.
     * Does not touch {@link #stats()}; callers own request accounting.
     */
    public SimulationResult simulate() {
        BackendConfig current = config.get();

        if (stats.activeConnections() >= current.capacity()) {
            return new SimulationResult(false, 0, FailureReason.CAPACITY_EXCEEDED);
        }

        if (random.nextDouble() < current.failureRate()) {
            return new SimulationResult(false, 0, FailureReason.BACKEND_FAILED);
        }

        try {
            Thread.sleep(current.latencyMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SimulationResult(false, 0, FailureReason.BACKEND_FAILED);
        }
        return new SimulationResult(true, current.latencyMs() * NANOS_PER_MILLI, FailureReason.NONE);
    }

    public void updateConfig(BackendConfig newConfig) {
        config.set(Objects.requireNonNull(newConfig, "config must not be null"));
    }

    public void setStatus(BackendStatus newStatus) {
        status.set(Objects.requireNonNull(newStatus, "status must not be null"));
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public BackendConfig config() {
        return config.get();
    }

    public BackendStatus status() {
        return status.get();
    }

    public BackendStats stats() {
        return stats;
    }
}