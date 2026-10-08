package com.example.lb.scenario;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.example.lb.backend.BackendStatus.DOWN;
import static com.example.lb.backend.BackendStatus.UP;

/**
 * Takes healthy backends down one after another with a gap between them, so the
 * dashboard can show how the balancer reacts to a failure that <em>spreads</em>
 * rather than arriving all at once.
 *
 * <p>The first backend goes down before {@link #apply()} returns — the cascade
 * has visibly started by the time the caller's click does — and the rest follow
 * every {@code delayMs} on the scheduler this scenario was given. Victims are
 * sampled from the healthy backends without replacement, so {@code count} drops
 * always hit {@code count} distinct backends; if the pool runs out of healthy
 * backends first, the remaining scheduled drops do nothing rather than failing.
 *
 * <p>{@link #revert()} cancels the drops that have not happened yet and brings
 * every backend this scenario took down back up. It does <em>not</em> shut the
 * scheduler down: the scheduler is shared with the traffic generator and the
 * health checker, and whoever created it owns it. A backend that was already
 * down for another reason is left alone, so a concurrent outage is not silently
 * undone.
 *
 * <p>The scenario takes no parameters: how many backends to drop and how long
 * between them are fixed when it is constructed, because a cascade whose pace is
 * decided by a hand-typed query parameter is not reproducible on the dashboard.
 */
public class CascadingFailureScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "cascading-failure";

    /** How many backends are dropped when the caller does not say. */
    static final int DEFAULT_COUNT = 2;

    /** Gap between drops when the caller does not say. */
    static final long DEFAULT_DELAY_MS = 50L;

    private final BackendPool pool;
    private final ScheduledExecutorService scheduler;
    private final int count;
    private final long delayMs;
    private final Random random;

    /** Ids this scenario took down, so revert restores exactly those. */
    private final Set<String> dropped = new LinkedHashSet<>();

    /** The drops that have not happened yet, cancelled by revert. */
    private final List<ScheduledFuture<?>> pending = new ArrayList<>();

    /**
     * Guards {@link #dropped}, {@link #pending} and {@link #applied}: the drops
     * run on the scheduler's threads while apply and revert run on the REST
     * thread, so a revert can land in the middle of a cascade.
     */
    private final Object lock = new Object();

    private boolean applied;

    public CascadingFailureScenario(BackendPool pool, ScheduledExecutorService scheduler) {
        this(pool, scheduler, DEFAULT_COUNT, DEFAULT_DELAY_MS);
    }

    public CascadingFailureScenario(BackendPool pool, ScheduledExecutorService scheduler,
                                    int count, long delayMs) {
        this(pool, scheduler, count, delayMs, new Random());
    }

    /**
     * Constructor with an injected random, so a test can pin which backends are
     * sampled. The seed is otherwise irrelevant: the cascade always hits
     * distinct backends.
     */
    public CascadingFailureScenario(BackendPool pool, ScheduledExecutorService scheduler,
                                    int count, long delayMs, Random random) {
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0, but was " + count);
        }
        if (delayMs < 0) {
            throw new IllegalArgumentException("delayMs must be >= 0, but was " + delayMs);
        }
        this.count = count;
        this.delayMs = delayMs;
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Takes healthy backends down one after another to show how the balancer copes with a spreading outage.";
    }

    @Override
    public void apply() {
        synchronized (lock) {
            if (applied) {
                return;
            }
            applied = true;
            // The first drop is synchronous: by the time apply() returns the
            // cascade has visibly started, which is what a dashboard click wants.
            dropOne();
            for (int i = 1; i < count; i++) {
                pending.add(scheduler.schedule(this::dropOne, i * delayMs, TimeUnit.MILLISECONDS));
            }
        }
    }

    @Override
    public void revert() {
        List<ScheduledFuture<?>> cancelled;
        Set<String> restore;
        synchronized (lock) {
            cancelled = new ArrayList<>(pending);
            pending.clear();
            restore = new LinkedHashSet<>(dropped);
            dropped.clear();
            applied = false;
        }
        // Cancelled outside the lock: a drop already running on the scheduler's
        // thread would otherwise hold it while waiting on us.
        for (ScheduledFuture<?> task : cancelled) {
            task.cancel(false);
        }
        for (String id : restore) {
            pool.get(id).ifPresent(backend -> backend.setStatus(UP));
        }
    }

    /**
     * Takes one healthy backend out of rotation. A no-op once the scenario has
     * been reverted — a drop that was already running when revert arrived must
     * not put a backend back down behind the revert.
     */
    private void dropOne() {
        synchronized (lock) {
            if (!applied) {
                return;
            }
            List<Backend> candidates = new ArrayList<>(pool.healthy());
            if (candidates.isEmpty()) {
                return;
            }
            Collections.shuffle(candidates, random);
            Backend victim = candidates.get(0);
            victim.setStatus(DOWN);
            dropped.add(victim.id());
        }
    }
}
