package com.example.lb.api.dto;

/**
 * Body of {@code POST /api/backends}.
 *
 * <p>The id is derived from {@link #name()} rather than supplied, so a client
 * cannot register two backends under one id by accident.
 *
 * @param latencyMs   simulated latency, or {@code null} for the default
 * @param failureRate failure probability, or {@code null} for the default
 * @param capacity    concurrent connection limit, or {@code null} for the default
 * @param weight      relative traffic share, or {@code null} for the default
 */
public record BackendCreateRequest(
        String name,
        Long latencyMs,
        Double failureRate,
        Integer capacity,
        Integer weight) {
}