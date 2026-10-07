package com.example.lb.backend;

/**
 * Immutable configuration of a backend.
 *
 * @param latencyMs   simulated processing time in milliseconds
 * @param failureRate probability in {@code [0.0, 1.0]} that a request fails
 * @param capacity    maximum number of concurrent connections
 * @param weight      relative share of traffic, at least {@code 1}
 */
public record BackendConfig(long latencyMs, double failureRate, int capacity, int weight) {

    public BackendConfig {
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must be >= 0, but was " + latencyMs);
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException("failureRate must be within [0.0, 1.0], but was " + failureRate);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, but was " + capacity);
        }
        if (weight < 1) {
            throw new IllegalArgumentException("weight must be >= 1, but was " + weight);
        }
    }

    /** Configuration used for backends that have not been tuned yet. */
    public static BackendConfig defaults() {
        return new BackendConfig(5, 0.0, 100, 1);
    }

    /**
     * Returns a copy of this config where every non-null argument replaces the
     * current value; {@code null} arguments keep the current value.
     */
    public BackendConfig copyWith(Long latencyMs, Double failureRate, Integer capacity, Integer weight) {
        return new BackendConfig(
                latencyMs != null ? latencyMs : this.latencyMs,
                failureRate != null ? failureRate : this.failureRate,
                capacity != null ? capacity : this.capacity,
                weight != null ? weight : this.weight);
    }
}