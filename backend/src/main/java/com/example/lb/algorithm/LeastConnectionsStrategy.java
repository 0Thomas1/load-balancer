package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the candidate with the fewest in-flight requests, ties going to the first listed. */
@Component
public class LeastConnectionsStrategy implements AlgorithmStrategy {

    public static final String NAME = "least-connections";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);

        Backend best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            Backend candidate = candidates.get(i);
            if (candidate.stats().activeConnections() < best.stats().activeConnections()) {
                best = candidate;
            }
        }
        return best;
    }
}