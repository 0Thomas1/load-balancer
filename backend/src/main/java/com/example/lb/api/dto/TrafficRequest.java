package com.example.lb.api.dto;

/**
 * Body of {@code PUT /api/traffic}: the traffic rate to aim at, optionally as a
 * burst.
 *
 * @param burstDurationMs how long the burst lasts; {@code null} or {@code 0}
 *                        sets the base rate instead
 */
public record TrafficRequest(long rate, Long burstDurationMs) {
}