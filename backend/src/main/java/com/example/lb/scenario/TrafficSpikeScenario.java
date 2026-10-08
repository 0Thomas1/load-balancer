package com.example.lb.scenario;

import java.util.Map;
import java.util.Objects;

/**
 * Raises the synthetic traffic generator to a much higher rate, so the dashboard
 * can show what the balancer's latency figures and health checks do under load.
 *
 * <p>The spike is a <em>persistent</em> burst rather than a change to the base
 * rate: it lasts until {@link #revert()}, which stops it and leaves the base rate
 * the generator was configured with. That keeps the spike from having to
 * remember the base rate to put it back, and it composes cleanly with anything
 * else that changes the target rate.
 *
 * <p>The generator eases towards the new rate rather than jumping to it, which
 * reads on the dashboard as a ramp; this scenario only starts and stops the
 * burst.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code rate} — the spike rate, default {@value #DEFAULT_RATE}. Unlike
 *       the other scenarios, a missing, unparsable or non-positive value is an
 *       error rather than a silent fallback: a spike of zero requests per
 *       second is not a weaker spike, it is no spike at all, so failing loudly
 *       beats quietly doing nothing.</li>
 * </ul>
 */
public class TrafficSpikeScenario implements Scenario {

    /** Registry key, relied upon by the REST layer and the dashboard. */
    public static final String NAME = "traffic-spike";

    /** Spike rate, in requests per second, when the caller passes no {@code rate}. */
    static final long DEFAULT_RATE = 200L;

    /** Burst duration meaning "no deadline": the spike lasts until revert. */
    private static final long PERSISTENT = 0L;

    private final TrafficGenerator generator;
    private final long defaultRate;

    /**
     * Whether a spike is currently in effect, so revert is a no-op without one.
     *
     * <p>The check-then-act around this flag is deliberately not synchronized:
     * apply and revert are serialized by the REST layer above, so the only
     * interleaving left to worry about would need two callers reaching the same
     * scenario at once, which nothing does. The one thing that must not go stale
     * is the flag itself, which is why it is volatile.
     */
    private volatile boolean applied;

    public TrafficSpikeScenario(TrafficGenerator generator) {
        this(generator, DEFAULT_RATE);
    }

    /** Constructor with an explicit default rate, so callers can pick their own spike. */
    public TrafficSpikeScenario(TrafficGenerator generator, long defaultRate) {
        this.generator = Objects.requireNonNull(generator, "generator must not be null");
        if (defaultRate <= 0) {
            throw new IllegalArgumentException("defaultRate must be > 0, but was " + defaultRate);
        }
        this.defaultRate = defaultRate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Spikes the synthetic traffic rate so the balancer can be watched under heavy load.";
    }

    @Override
    public void apply() {
        apply(Map.of());
    }

    @Override
    public void apply(Map<String, String> params) {
        generator.setBurst(requestedRate(params), PERSISTENT);
        applied = true;
    }

    @Override
    public void revert() {
        if (!applied) {
            return;
        }
        applied = false;
        generator.stopBurst();
    }

    /**
     * Reads the {@code rate} parameter, falling back to {@link #defaultRate} when
     * it is absent.
     *
     * @throws IllegalArgumentException when the value is not a positive number,
     *                                  which includes an unparsable one — that
     *                                  surfaces as a
     *                                  {@link NumberFormatException}
     */
    private long requestedRate(Map<String, String> params) {
        String raw = params == null ? null : params.get("rate");
        if (raw == null) {
            return defaultRate;
        }
        long rate = Long.parseLong(raw.trim());
        if (rate <= 0) {
            throw new IllegalArgumentException("rate must be > 0, but was " + rate);
        }
        return rate;
    }
}
