package com.example.lb.backend;

/**
 * Outcome of a single simulated request against a backend.
 *
 * @param success       whether the request succeeded
 * @param latencyNanos  latency of the attempt in nanoseconds, {@code 0} for
 *                      attempts rejected before reaching the backend
 * @param reason        failure cause, {@link FailureReason#NONE} on success
 */
public record SimulationResult(boolean success, long latencyNanos, FailureReason reason) {
}