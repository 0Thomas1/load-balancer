package com.example.lb.metrics;

import java.util.List;

/**
 * Immutable snapshot of everything the dashboard renders.
 *
 * @param timestamp      wall-clock time the snapshot was taken, in milliseconds
 * @param requestsPerSecond arrivals still inside the collector's rolling window
 */
public record MetricsSnapshot(
        long timestamp,
        long totalRequests,
        int requestsPerSecond,
        long errorCount,
        String activeAlgorithm,
        List<String> activeScenarios,
        List<BackendView> backends) {

    public MetricsSnapshot {
        activeScenarios = List.copyOf(activeScenarios);
        backends = List.copyOf(backends);
    }
}