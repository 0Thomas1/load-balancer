package com.example.lb.api.dto;

/**
 * Body of {@code PUT /api/backends/{id}/config}: a partial update of a backend's
 * configuration, where every {@code null} field keeps its current value.
 */
public record BackendConfigUpdateRequest(
        Long latencyMs,
        Double failureRate,
        Integer capacity,
        Integer weight) {
}