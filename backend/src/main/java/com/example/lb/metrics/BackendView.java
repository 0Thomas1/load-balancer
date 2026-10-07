package com.example.lb.metrics;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendStatus;
import com.example.lb.backend.CircuitState;

/**
 * Immutable projection of a {@link Backend} for the dashboard.
 *
 * @param avgLatency mean latency in milliseconds
 */
public record BackendView(
        String id,
        String name,
        BackendStatus status,
        int weight,
        int activeConnections,
        long totalRequests,
        long errorCount,
        double avgLatency,
        CircuitState circuitState) {

    /** Reads the backend's current state and counters into a view. */
    public static BackendView from(Backend backend) {
        var stats = backend.stats();
        return new BackendView(
                backend.id(),
                backend.name(),
                backend.status(),
                backend.config().weight(),
                stats.activeConnections(),
                stats.totalRequests(),
                stats.errorCount(),
                stats.avgLatencyMs(),
                stats.circuitState());
    }
}