package com.example.lb.metrics;

import com.example.lb.backend.BackendPool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects load-balancer-wide request counters and a rolling arrival rate.
 *
 * <p>Every attempt is counted here, including unroutable failures
 * ({@code NO_BACKENDS} / {@code CIRCUIT_OPEN}), so that they reach the dashboard
 * even though no backend saw them. Per-backend counts live in each
 * {@code Backend.stats()}.
 *
 * <p>The arrival window is mutated by traffic threads and read by the WebSocket
 * thread, so all window access is synchronized. The counters are atomic and
 * lock-free.
 */
public class MetricsCollector {

    private static final long DEFAULT_WINDOW_MS = 1_000L;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final Deque<Long> window = new ArrayDeque<>();
    private final long windowMs;

    public MetricsCollector() {
        this(DEFAULT_WINDOW_MS);
    }

    /** @param windowMs width of the rolling arrival window in milliseconds */
    public MetricsCollector(long windowMs) {
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be > 0, but was " + windowMs);
        }
        this.windowMs = windowMs;
    }

    /** Records one routing attempt, successful or not. */
    public void record(boolean success) {
        totalRequests.incrementAndGet();
        if (!success) {
            errorCount.incrementAndGet();
        }
        synchronized (window) {
            long now = System.currentTimeMillis();
            pruneBefore(now - windowMs);
            window.addLast(now);
        }
    }

    /** Number of arrivals inside the rolling window, pruning stale ones first. */
    public int requestsPerSecond() {
        synchronized (window) {
            pruneBefore(System.currentTimeMillis() - windowMs);
            return window.size();
        }
    }

    /** Drops every arrival at or before {@code cutoff}. Callers hold the window lock. */
    private void pruneBefore(long cutoff) {
        while (!window.isEmpty() && window.peekFirst() <= cutoff) {
            window.removeFirst();
        }
    }

    public long totalRequests() {
        return totalRequests.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    /**
     * Composes a point-in-time view of the whole balancer.
     *
     * @param activeScenarios names of the running scenarios; copied defensively
     */
    public MetricsSnapshot snapshot(BackendPool pool, String algorithmName, List<String> activeScenarios) {
        Objects.requireNonNull(pool, "pool must not be null");
        Objects.requireNonNull(algorithmName, "algorithmName must not be null");
        Objects.requireNonNull(activeScenarios, "activeScenarios must not be null");
        return new MetricsSnapshot(
                System.currentTimeMillis(),
                totalRequests(),
                requestsPerSecond(),
                errorCount(),
                algorithmName,
                activeScenarios,
                pool.all().stream().map(BackendView::from).toList());
    }
}