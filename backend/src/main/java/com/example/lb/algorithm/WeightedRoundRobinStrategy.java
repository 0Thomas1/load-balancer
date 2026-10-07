package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Walks a weighted cycle: every tick falls into one backend's slice of the
 * cumulative weight, so a backend with weight 3 gets three ticks per cycle.
 * Falls back to plain rotation when the weights sum to zero.
 */
@Component
public class WeightedRoundRobinStrategy implements AlgorithmStrategy {

    public static final String NAME = "weighted-round-robin";

    private final AtomicLong counter = new AtomicLong();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);

        int total = 0;
        for (Backend candidate : candidates) {
            total += candidate.config().weight();
        }
        long tick = counter.getAndIncrement();
        if (total <= 0) {
            return candidates.get((int) (tick % candidates.size()));
        }

        int position = (int) (tick % total);
        int cumulative = 0;
        for (Backend candidate : candidates) {
            cumulative += candidate.config().weight();
            if (position < cumulative) {
                return candidate;
            }
        }
        throw new IllegalStateException("weighted selection fell outside the cycle");
    }
}