package com.example.lb.api.dto;

/** Body of {@code PUT /api/algorithm}: the algorithm to activate. */
public record AlgorithmSwitchRequest(String name) {
}