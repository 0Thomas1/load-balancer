package com.example.lb.scenario;

import com.example.lb.balancer.LoadBalancer;
import com.example.lb.balancer.RoutingResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Emits synthetic traffic at a configurable rate, so the dashboard and the
 * scenarios have something to show without any real client.
 *
 * <p>The generator never jumps straight to a newly configured rate: the current
 * rate eases towards the target by {@code TICK_INTERVAL_MS / RAMP_MS} per tick,
 * which reads on the dashboard as a ramp over roughly five seconds. Every tick
 * sends {@code max(1, floor(currentRate / 10))} requests through
 * {@link LoadBalancer#route(String)} and then publishes the outcome to every
 * {@link RequestEventListener}.
 *
 * <p>A burst temporarily raises the effective target, either until
 * {@link #stopBurst()} or until its duration elapses; expiry is checked on the
 * next tick, so no timer of its own is needed and the tests can drive it with an
 * injected clock.
 *
 * <p>The target, burst state and current rate are written by the REST threads
 * and read by the generating thread, so they are volatile. Racing a
 * reconfiguration against a tick costs at most one tick of stale behaviour.
 */
public class TrafficGenerator implements AutoCloseable {

    /** How often a tick runs; also the divisor that turns a rate into requests. */
    static final long TICK_INTERVAL_MS = 100L;

    /** Time constant of the ease towards the target. */
    static final long RAMP_MS = 5_000L;

    /** Rate the generator starts at, so the dashboard is not empty at boot. */
    private static final long DEFAULT_TARGET_RATE = 20L;

    private final LoadBalancer loadBalancer;
    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private final List<RequestEventListener> listeners = new CopyOnWriteArrayList<>();

    private volatile long baseTargetRate = DEFAULT_TARGET_RATE;
    private volatile long burstTargetRate;
    private volatile long burstEndsAtMillis;
    private volatile double currentRate;
    private volatile ScheduledFuture<?> schedule;

    public TrafficGenerator(LoadBalancer loadBalancer, ScheduledExecutorService scheduler) {
        this(loadBalancer, scheduler, System::currentTimeMillis);
    }

    /** Test seam: lets the caller supply the clock the burst duration is measured against. */
    TrafficGenerator(LoadBalancer loadBalancer, ScheduledExecutorService scheduler, LongSupplier clock) {
        this.loadBalancer = Objects.requireNonNull(loadBalancer, "loadBalancer must not be null");
        this.scheduler = scheduler;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Schedules the tick every {@link #TICK_INTERVAL_MS} milliseconds, unless a
     * schedule already exists. A generator without a scheduler still works: it
     * simply relies on someone calling {@link #tick()}.
     */
    public void start() {
        if (scheduler == null || schedule != null) {
            return;
        }
        schedule = scheduler.scheduleWithFixedDelay(
                this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /** Cancels the schedule and shuts the scheduler down. */
    @Override
    public void close() {
        ScheduledFuture<?> pending = schedule;
        schedule = null;
        if (pending != null) {
            pending.cancel(false);
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Emits one tick's worth of requests and publishes their outcomes.
     *
     * <p>Public so a scenario or a test can drive the generator at its own pace.
     */
    public void tick() {
        expireBurst();
        long target = targetRate();
        // Ease towards the target. The step is proportional, so the rate
        // converges geometrically and never overshoots; the floor keeps a
        // decayed rate from going negative.
        currentRate = Math.max(0.0, currentRate + (target - currentRate) * (TICK_INTERVAL_MS / (double) RAMP_MS));

        int requests = Math.max(1, (int) Math.floor(currentRate / 10));
        for (int i = 0; i < requests; i++) {
            emit();
        }
    }

    /** Sends one synthetic request and publishes the outcome. */
    private void emit() {
        String sourceIp = "10.0.0." + (ThreadLocalRandom.current().nextInt(254) + 1);
        RoutingResult result = loadBalancer.route(sourceIp);
        RequestEvent event = new RequestEvent(
                sourceIp, result.backendId(), result.success(), result.latencyNanos(), result.reason());
        for (RequestEventListener listener : listeners) {
            listener.onRequest(event);
        }
    }

    /**
     * Sets the base rate the generator eases towards.
     *
     * @throws IllegalArgumentException when {@code rate} is not positive
     */
    public void setTargetRate(long rate) {
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be > 0, but was " + rate);
        }
        baseTargetRate = rate;
    }

    /**
     * Raises the effective target for a while.
     *
     * @param rate       the burst rate, at least {@code 1}
     * @param durationMs how long the burst lasts; {@code 0} keeps it running
     *                   until {@link #stopBurst()}
     * @throws IllegalArgumentException when {@code rate} is not positive or
     *                                  {@code durationMs} is negative
     */
    public void setBurst(long rate, long durationMs) {
        if (rate <= 0) {
            throw new IllegalArgumentException("burst rate must be > 0, but was " + rate);
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("burst duration must be >= 0, but was " + durationMs);
        }
        burstTargetRate = rate;
        burstEndsAtMillis = durationMs == 0 ? 0 : clock.getAsLong() + durationMs;
    }

    /** Ends the burst, if any, and returns to the base rate immediately. */
    public void stopBurst() {
        burstTargetRate = 0;
        burstEndsAtMillis = 0;
    }

    /** Whether a burst rate is currently in effect, expired or not. */
    public boolean isBursting() {
        return burstTargetRate > 0;
    }

    /** The rate the generator is easing towards: the burst rate while bursting. */
    public long targetRate() {
        return burstTargetRate > 0 ? burstTargetRate : baseTargetRate;
    }

    /** The rate currently being emitted, in requests per second. */
    public double currentRate() {
        return currentRate;
    }

    /** Registers a listener; it is notified once per emitted request. */
    public void addListener(RequestEventListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener must not be null"));
    }

    /** Drops an expired burst so it stops being the effective target. */
    private void expireBurst() {
        long endsAt = burstEndsAtMillis;
        if (burstTargetRate > 0 && endsAt > 0 && clock.getAsLong() >= endsAt) {
            stopBurst();
        }
    }
}