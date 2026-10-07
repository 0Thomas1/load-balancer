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