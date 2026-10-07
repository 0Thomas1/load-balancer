package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Hands out candidates in strict rotation. */
@Component
public class RoundRobinStrategy implements AlgorithmStrategy {

    public static final String NAME = "round-robin";

    private final AtomicLong counter = new AtomicLong();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);
        return candidates.get((int) (counter.getAndIncrement() % candidates.size()));
    }
}