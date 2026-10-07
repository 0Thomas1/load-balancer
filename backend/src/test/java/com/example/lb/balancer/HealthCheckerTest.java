package com.example.lb.balancer;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import com.example.lb.backend.BackendPool;
import com.example.lb.backend.BackendStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthCheckerTest {

    private static final long PROBE_INTERVAL_MS = 50;
    private static final int FAILURE_THRESHOLD = 3;
    private static final long PROBE_TIMEOUT_MS = 500;

    private BackendPool pool;
    private HealthChecker checker;

    @BeforeEach
    void setUp() {
        pool = new BackendPool();
        checker = new HealthChecker(pool, PROBE_INTERVAL_MS, FAILURE_THRESHOLD, PROBE_TIMEOUT_MS);
    }

    @Test
    void overloadedBackendGoesDownAfterConsecutiveFailedProbes() {
        Backend b = backend("b1", new BackendConfig(5, 0.0, 1, 1));
        b.stats().incrementActive();
        pool.add(b);

        assertFalse(checker.probe(b), "at capacity means the probe fails");

        checker.probeAll();
        assertEquals(BackendStatus.UP, b.status(), "one failed probe is not enough");
        checker.probeAll();
        assertEquals(BackendStatus.UP, b.status(), "two failed probes are not enough");

        checker.probeAll();
        assertEquals(BackendStatus.DOWN, b.status(), "flips DOWN at the threshold");
    }

    @Test
    void tooSlowBackendGoesDownAfterConsecutiveFailedProbes() {
        Backend b = backend("b1", new BackendConfig(600, 0.0, 100, 1));
        pool.add(b);

        assertFalse(checker.probe(b), "slower than the probe timeout means the probe fails");

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            checker.probeAll();
        }

        assertEquals(BackendStatus.DOWN, b.status());
    }

    @Test
    void healthyBackendStaysUpAcrossManyProbes() {
        Backend b = backend("b1", BackendConfig.defaults());
        pool.add(b);

        assertTrue(checker.probe(b), "an idle, fast backend passes its probe");

        for (int i = 0; i < 20; i++) {
            checker.probeAll();
        }

        assertEquals(BackendStatus.UP, b.status());
    }

    @Test
    void downBackendStaysDownEvenAfterItsHealthRecovers() {
        Backend b = backend("b1", new BackendConfig(5, 0.0, 1, 1));
        b.stats().incrementActive();
        pool.add(b);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            checker.probeAll();
        }
        assertEquals(BackendStatus.DOWN, b.status());

        // The overload clears, so the backend is healthy again -- but recovery
        // is explicit (recover(backend)), never automatic: a checker that
        // resurrected backends on its own would silently undo scenarios like
        // Consumer Drop.
        b.stats().decrementActive();
        assertTrue(checker.probe(b), "health has recovered");

        for (int i = 0; i < 20; i++) {
            checker.probeAll();
        }

        assertEquals(BackendStatus.DOWN, b.status(), "DOWN backends are not re-probed");
    }

    @Test
    void recoverBringsADownBackendBackUp() {
        Backend b = backend("b1", new BackendConfig(5, 0.0, 1, 1));
        b.stats().incrementActive();
        pool.add(b);

        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            checker.probeAll();
        }
        assertEquals(BackendStatus.DOWN, b.status());

        checker.recover(b);
        assertEquals(BackendStatus.UP, b.status());
    }

    @Test
    void drainingBackendIsNeitherProbedNorFlipped() {
        Backend draining = backend("d1", new BackendConfig(600, 0.0, 1, 1));
        draining.setStatus(BackendStatus.DRAINING);
        draining.stats().incrementActive();
        pool.add(draining);

        Backend up = backend("b1", new BackendConfig(5, 0.0, 100, 1));
        pool.add(up);

        for (int i = 0; i < 20; i++) {
            checker.probeAll();
        }

        assertEquals(BackendStatus.DRAINING, draining.status(),
                "a draining backend is skipped, even though every probe would fail");
        assertEquals(BackendStatus.UP, up.status());
    }

    private Backend backend(String id, BackendConfig config) {
        return new Backend(id, id, config);
    }
}