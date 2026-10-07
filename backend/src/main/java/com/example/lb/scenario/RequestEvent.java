package com.example.lb.scenario;

import com.example.lb.backend.FailureReason;

/**
 * One synthetic request as the {@link TrafficGenerator} emitted it, published to
 * every {@link RequestEventListener} once the balancer has answered.
 *
 * @param sourceIp     the synthetic client the request came from
 * @param backendId    id of the backend that served the attempt, or {@code null}
 *                     when nothing was routed
 * @param success      whether the request succeeded
 * @param latencyNanos latency of the attempt in nanoseconds
 * @param reason       why the attempt failed, {@link FailureReason#NONE} on success
 */
public record RequestEvent(String sourceIp, String backendId, boolean success, long latencyNanos,
                           FailureReason reason) {
}