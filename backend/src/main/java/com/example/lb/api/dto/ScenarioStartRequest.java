package com.example.lb.api.dto;

import java.util.Map;

/**
 * Body of {@code POST /api/scenarios/{name}}: the scenario's parameters, which
 * may be empty to take the scenario's own defaults.
 */
public record ScenarioStartRequest(Map<String, String> params) {
}