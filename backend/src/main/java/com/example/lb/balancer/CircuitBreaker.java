package com.example.lb.balancer;

import com.example.lb.backend.CircuitState;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import static com.example.lb.backend.CircuitState.CLOSED;
import static com.example.lb.backend.CircuitState.HALF_OPEN;
import static com.example.lb.backend.CircuitState.OPEN;

/**
 * Per-backend circuit breaker.
 *
 * <p>A backend starts {@link CircuitState#CLOSED} and opens once it has failed
 * {@code failureThreshold} times in a row. An open backend is skipped until
 * {@code resetTimeoutMs} has elapsed, at which point the circuit moves to
 * {@link CircuitState#HALF_OPEN} and admits exactly one probe request: a probe
 * success closes the circuit, a probe failure re-opens it and re-arms the
 * timeout. Each backend keeps its own independent state, initialised lazily on
 * first use, so unknown ids behave like closed circuits.
 *
 * <p>Reads are lock-free; the transitions that mutate a backend's state are
 * serialized on that backend's own circuit object, which keeps the "exactly one
 * probe per half-open window" guarantee without any global lock.
 */
public class CircuitBreaker {

    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final LongSupplier clock;
    private final Map<String, BackendCircuit> circuits = new ConcurrentHashMap<>();

    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this(failureThreshold, resetTimeoutMs, System::currentTimeMillis);
    }

    /** Test seam: lets the caller supply the clock the timeouts are measured against. */
    CircuitBreaker(int failureThreshold, long resetTimeoutMs, LongSupplier clock) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (resetTimeoutMs < 0) {
            throw new IllegalArgumentException("resetTimeoutMs must be >= 0");
        }
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Whether a request may be sent to the given backend. Moves an expired
     * {@link CircuitState#OPEN} circuit to {@link CircuitState#HALF_OPEN}, and
     * admits a single probe while half-open.
     */
    public boolean allowRequest(String backendId) {
        BackendCircuit circuit = circuitFor(backendId);
        synchronized (circuit) {
            if (circuit.state == CLOSED) {
                return true;
            }
            if (circuit.state == OPEN) {
                if (!timeoutElapsed(circuit)) {
                    return false;
                }
                circuit.state = HALF_OPEN;
            }
            if (circuit.probeInFlight) {
                // Edge case: a half-open probe whose outcome never arrives
                // (client hung, connection dropped without an error) leaves
                // probeInFlight stuck true and the backend stays half-open with
                // no further probes admitted. Callers are responsible for always
                // reporting a terminal outcome for an admitted probe; if they
                // cannot guarantee that, reset(id) is the manual escape hatch.
                return false;
            }
            circuit.probeInFlight = true;
            return true;
        }
    }

    /**
     * Current state of the backend's circuit. An open circuit whose timeout has
     * elapsed is reported as {@link CircuitState#HALF_OPEN} (without consuming
     * the probe, which only {@link #allowRequest(String)} does).
     */
    public CircuitState state(String backendId) {
        BackendCircuit circuit = circuitFor(backendId);
        synchronized (circuit) {
            if (circuit.state == OPEN && timeoutElapsed(circuit)) {
                circuit.state = HALF_OPEN;
            }
            return circuit.state;
        }
    }

    /** Whether the backend's circuit is currently {@link CircuitState#OPEN}. */
    public boolean isOpen(String backendId) {
        return state(backendId) == OPEN;
    }

    /**
     * Records a successful request: closes the circuit (a half-open probe, or a
     * late success from a request that was in flight when the circuit opened)
     * and clears the failure count.
     */
    public void onSuccess(String backendId) {
        close(circuitFor(backendId));
    }

    /** Records a failed request: opens the circuit at the threshold, or re-opens it from half-open. */
    public void onFailure(String backendId) {
        BackendCircuit circuit = circuitFor(backendId);
        synchronized (circuit) {
            if (circuit.state == HALF_OPEN) {
                open(circuit);
                return;
            }
            if (circuit.state == OPEN) {
                return;
            }
            circuit.consecutiveFailures++;
            if (circuit.consecutiveFailures >= failureThreshold) {
                open(circuit);
            }
        }
    }

    /** Forces the backend's circuit closed and forgets any recorded failures. */
    public void reset(String backendId) {
        close(circuitFor(backendId));
    }

    private void close(BackendCircuit circuit) {
        synchronized (circuit) {
            circuit.consecutiveFailures = 0;
            circuit.probeInFlight = false;
            circuit.state = CLOSED;
        }
    }

    private boolean timeoutElapsed(BackendCircuit circuit) {
        return clock.getAsLong() - circuit.openedAt >= resetTimeoutMs;
    }

    private void open(BackendCircuit circuit) {
        circuit.state = OPEN;
        circuit.openedAt = clock.getAsLong();
        circuit.probeInFlight = false;
    }

    private BackendCircuit circuitFor(String backendId) {
        Objects.requireNonNull(backendId, "backendId must not be null");
        return circuits.computeIfAbsent(backendId, id -> new BackendCircuit());
    }

    private static final class BackendCircuit {
        private volatile CircuitState state = CLOSED;
        private int consecutiveFailures;
        private long openedAt;
        private volatile boolean probeInFlight;
    }
}
