package com.example.lb.algorithm;

import com.example.lb.backend.Backend;

import java.util.List;

/** Selects one backend out of the currently healthy candidates. */
public interface AlgorithmStrategy {

    /** Stable identifier used to look the strategy up by name. */
    String getName();

    /**
     * Picks a backend from {@code candidates}.
     *
     * @param candidates healthy backends, in stable list order; must not be empty
     * @param sourceIp   the requesting client's IP, may be {@code null}
     * @return the selected backend, never {@code null}
     * @throws IllegalArgumentException if {@code candidates} is empty
     */
    Backend select(List<Backend> candidates, String sourceIp);

    /** Guards against selecting from an empty candidate list. */
    static void requireNonEmpty(List<Backend> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("no candidates");
        }
    }
}