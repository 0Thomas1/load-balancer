package com.example.lb.balancer;

import com.example.lb.backend.CircuitState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long RESET_TIMEOUT_MS = 50;

    private FakeClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        breaker = new CircuitBreaker(FAILURE_THRESHOLD, RESET_TIMEOUT_MS, clock);
    }

    @Test
    void opensAfterConsecutiveFailuresReachThreshold() {
        assertTrue(breaker.allowRequest("b1"));
        assertEquals(CircuitState.CLOSED, breaker.state("b1"));

        breaker.onFailure("b1");
        breaker.onFailure("b1");
        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
        assertTrue(breaker.allowRequest("b1"));

        breaker.onFailure("b1");
        assertEquals(CircuitState.OPEN, breaker.state("b1"));
        assertTrue(breaker.isOpen("b1"));
        assertFalse(breaker.allowRequest("b1"));
    }

    @Test
    void halfOpensAfterResetTimeoutAndAdmitsExactlyOneProbe() {
        openB1();

        clock.advance(RESET_TIMEOUT_MS - 1);
        assertEquals(CircuitState.OPEN, breaker.state("b1"));
        assertFalse(breaker.allowRequest("b1"));

        clock.advance(1);
        assertEquals(CircuitState.HALF_OPEN, breaker.state("b1"));
        assertTrue(breaker.allowRequest("b1"), "the first half-open probe is admitted");
        assertFalse(breaker.allowRequest("b1"), "no second probe while one is in flight");
    }

    @Test
    void closesOnSuccessInHalfOpen() {
        openB1();
        clock.advance(RESET_TIMEOUT_MS);
        assertTrue(breaker.allowRequest("b1"));

        breaker.onSuccess("b1");

        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
        assertFalse(breaker.isOpen("b1"));
        assertTrue(breaker.allowRequest("b1"));
    }

    @Test
    void reopensOnFailureInHalfOpen() {
        openB1();
        clock.advance(RESET_TIMEOUT_MS);
        assertTrue(breaker.allowRequest("b1"));

        breaker.onFailure("b1");

        assertEquals(CircuitState.OPEN, breaker.state("b1"));
        assertTrue(breaker.isOpen("b1"));
        assertFalse(breaker.allowRequest("b1"));
    }

    @Test
    void resetClosesAnOpenCircuit() {
        openB1();

        breaker.reset("b1");

        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
        assertFalse(breaker.isOpen("b1"));
        assertTrue(breaker.allowRequest("b1"));

        // The failure counter is cleared too, so it takes a full threshold to re-open.
        breaker.onFailure("b1");
        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
    }

    @Test
    void successWhileOpenClosesTheCircuit() {
        openB1();
        assertFalse(breaker.allowRequest("b1"));

        // Fail-toward-availability: a success arriving from a request that was
        // already in flight when the circuit tripped closes it, rather than
        // being ignored. Note this intentionally overlaps with reset() -- the
        // two paths are identical today, and this test is the canary that
        // breaks if they ever diverge.
        breaker.onSuccess("b1");

        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
        assertFalse(breaker.isOpen("b1"));
        assertTrue(breaker.allowRequest("b1"));

        // The failure counter is cleared as well, so a full threshold of
        // failures is needed before the circuit trips again.
        breaker.onFailure("b1");
        breaker.onFailure("b1");
        assertEquals(CircuitState.CLOSED, breaker.state("b1"));
        breaker.onFailure("b1");
        assertEquals(CircuitState.OPEN, breaker.state("b1"));
    }

    @Test
    void failureWhileOpenIsANoOpAndDoesNotRearmTheResetTimeout() {
        // A longer timeout than the suite default, so that "armed when the
        // circuit opened" and "re-armed by the last failure while open" yield
        // two distinguishable deadlines (200ms apart in this scenario).
        final long timeoutMs = 200;
        CircuitBreaker slow = new CircuitBreaker(FAILURE_THRESHOLD, timeoutMs, clock);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            slow.onFailure("b1");
        }
        final long openedAt = clock.getAsLong();
        assertEquals(CircuitState.OPEN, slow.state("b1"));

        // More failures land while the circuit is open -- requests that were
        // already in flight when it tripped. They must not re-arm the timeout,
        // and must not touch the consecutive-failure count either.
        clock.advance(150);
        slow.onFailure("b1");
        slow.onFailure("b1");
        assertEquals(CircuitState.OPEN, slow.state("b1"));

        // Probe a moment past the deadline measured from the OPEN transition...
        clock.advance(60);
        assertTrue(openedAt + timeoutMs <= clock.getAsLong(), "sanity: past the timeout since OPEN");
        assertTrue(clock.getAsLong() < openedAt + 150 + timeoutMs,
                "sanity: still short of the deadline re-arming at the last failure would imply");
        assertEquals(CircuitState.HALF_OPEN, slow.state("b1"),
                "the reset timeout is measured from the OPEN transition, not from the last failure while open");
        assertTrue(slow.allowRequest("b1"));
    }

    @Test
    void unknownBackendIdBehavesLikeAClosedCircuit() {
        assertEquals(CircuitState.CLOSED, breaker.state("ghost"));
        assertFalse(breaker.isOpen("ghost"));
        assertTrue(breaker.allowRequest("ghost"));

        breaker.onSuccess("ghost");
        assertEquals(CircuitState.CLOSED, breaker.state("ghost"));
    }

    private void openB1() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.onFailure("b1");
        }
        assertEquals(CircuitState.OPEN, breaker.state("b1"));
    }

    /** Hand-advanced clock so the timing assertions are deterministic. */
    private static final class FakeClock implements LongSupplier {
        private long nowMs = 1_000L;

        @Override
        public long getAsLong() {
            return nowMs;
        }

        void advance(long deltaMs) {
            nowMs += deltaMs;
        }
    }
}
