package com.example.lb.scenario;

/**
 * A scenario as the REST layer sees it: the registry entry plus whether it is
 * currently applied.
 */
public record ScenarioInfo(String name, String description, boolean active) {
}