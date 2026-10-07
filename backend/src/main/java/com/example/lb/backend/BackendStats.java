package com.example.lb.backend;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Thread-safe counters collected for a backend. */
public class BackendStats {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final AtomicInteger activeConnections = new AtomicInteger();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong totalLatencyNanos = new AtomicLong();
    private final AtomicReference<CircuitState> circuitState = new AtomicReference<>(CircuitState.CLOSED);

    public void incrementActive() {
        activeConnections.incrementAndGet();
    }

    public void decrementActive() {
        activeConnections.decrementAndGet();
    }

    public void recordSuccess(long latencyNanos) {
        totalRequests.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
    }

    public void recordFailure(long latencyNanos) {
        totalRequests.incrementAndGet();
        errorCount.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
    }

    public void setCircuitState(CircuitState state) {
        circuitState.set(state);
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    /** Mean latency of all recorded requests in milliseconds, {@code 0} when none. */
    public double avgLatencyMs() {
        long requests = totalRequests.get();
        if (requests == 0) {
            return 0.0;
        }
        return (double) totalLatencyNanos.get() / requests / NANOS_PER_MILLI;
    }

    public CircuitState circuitState() {
        return circuitState.get();
    }
}