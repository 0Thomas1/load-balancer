package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/** Picks a uniformly random candidate. */
@Component
public class RandomStrategy implements AlgorithmStrategy {

    public static final String NAME = "random";

    private final Random random;

    public RandomStrategy() {
        this(System.nanoTime());
    }

    /** Constructor with a fixed seed, for deterministic tests. */
    public RandomStrategy(long seed) {
        this.random = new Random(seed);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);
        return candidates.get(random.nextInt(candidates.size()));
    }
}