package com.example.lb.balancer;

import com.example.lb.backend.FailureReason;

/**
 * Outcome of one routing attempt.
 *
 * @param backendId     id of the backend that served the attempt, or {@code null}
 *                      when nothing was routed ({@code NO_BACKENDS} /
 *                      {@code CIRCUIT_OPEN})
 * @param success       whether the request succeeded
 * @param latencyNanos  latency of the attempt in nanoseconds, {@code 0} for
 *                      attempts that never reached a backend
 * @param reason        why the attempt failed, {@link FailureReason#NONE} on success
 */
public record RoutingResult(String backendId, boolean success, long latencyNanos, FailureReason reason) {

    /** An attempt that never reached a backend. */
    public static RoutingResult unroutable(FailureReason reason) {
        return new RoutingResult(null, false, 0L, reason);
    }
}